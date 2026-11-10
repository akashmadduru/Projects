package com.acme.ecommerce.auth.token;

import com.acme.ecommerce.auth.config.AuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Loads the RSA signing key(s) from a mounted PKCS#12 keystore and publishes the public half.
 *
 * <p>THE KEY IS NEVER GENERATED AT STARTUP. A self-generated key is a different key on every
 * replica and after every restart, so half the fleet cannot verify the other half's tokens and
 * every deploy silently logs everyone out. Worse, the failure is intermittent and looks like a
 * caching bug. If the keystore is missing, unreadable, or holds no RSA key, this class throws and
 * the application does not start - a service that cannot sign tokens has nothing useful to do.
 *
 * <p>SUPPORTS MULTIPLE SIMULTANEOUS KEYS, which is what makes zero-downtime rotation possible:
 *
 * <ol>
 *   <li>add key B to the keystore and redeploy - B is now published in JWKS and every resource
 *       server will accept it, but A is still the signer;
 *   <li>wait for every consumer to have refreshed its JWKS cache;
 *   <li>flip {@code auth.jwt.active-kid} to B - new tokens are signed with B, tokens already issued
 *       under A still verify;
 *   <li>after the longest access-token TTL has elapsed, remove A from the keystore.
 * </ol>
 *
 * <p>Skipping step 1 or 3 is what produces the classic "everyone got logged out during the
 * rotation" incident.
 *
 * <p>THE PRIVATE KEY MUST NOT LEAVE THIS CLASS. {@link #activeSigningKey()} is package-private, so
 * only {@link AccessTokenIssuer} can reach it; {@link #publicJwkSet()} is what the world sees, and
 * it is built with {@link JWK#toPublicJWK()} on every entry.
 */
@Component
public class JwtKeyProvider {

  private static final Logger log = LoggerFactory.getLogger(JwtKeyProvider.class);

  private static final List<String> RESOURCE_PREFIXES =
      List.of("classpath:", "file:", "http:", "https:");

  /** kid -> key, insertion-ordered so the JWKS document is stable across restarts. */
  private final Map<String, RSAKey> keysByKid;

  private final RSAKey activeKey;

  public JwtKeyProvider(AuthProperties properties) {
    AuthProperties.Jwt jwt = properties.getJwt();
    Map<String, String> aliasToKid = new LinkedHashMap<>();
    this.keysByKid = loadKeys(jwt, aliasToKid);
    this.activeKey = selectActiveKey(jwt.activeKid(), aliasToKid);

    log.info(
        "Loaded {} JWT signing key(s) from {}; active kid={}",
        keysByKid.size(),
        jwt.keystorePath(),
        activeKey.getKeyID());
  }

  /**
   * The key new tokens are signed with. PACKAGE-PRIVATE: the private key material must not be
   * reachable from a controller or an arbitrary service.
   */
  RSAKey activeSigningKey() {
    return activeKey;
  }

  /** The {@code kid} written into the JWT header. */
  String activeKid() {
    return activeKey.getKeyID();
  }

  /**
   * The public JWKS document served at {@code /.well-known/jwks.json}.
   *
   * <p>{@link JWK#toPublicJWK()} is applied to every entry, which is what strips {@code d, p, q,
   * dp, dq, qi}. Publishing those would hand every attacker the ability to mint tokens for any user
   * and any role in the platform - the single highest-consequence mistake this service could make.
   * There is an explicit test asserting they are absent.
   */
  public JWKSet publicJwkSet() {
    List<JWK> publicKeys = new ArrayList<>(keysByKid.size());
    for (RSAKey key : keysByKid.values()) {
      publicKeys.add(key.toPublicJWK());
    }
    return new JWKSet(publicKeys);
  }

  /**
   * Derives a symmetric secret for an unrelated purpose from the signing key.
   *
   * <p>Used by the cookie-based OAuth2 authorization-request repository, which needs an HMAC key
   * that is IDENTICAL ON EVERY REPLICA (or a login started on instance A cannot complete on
   * instance B) and NOT PUBLIC. The keystore is the only secret every replica already shares, so
   * deriving from it avoids inventing a second secret that operators must remember to distribute -
   * and a forgotten one would either break horizontal scaling or, worse, get a hardcoded default.
   *
   * <p>The derivation is a PRF over the PRIVATE exponent, not over the key's thumbprint. The
   * thumbprint is the {@code kid} and is published in JWKS, so deriving from it would let anyone
   * compute the HMAC key and forge authorization-request cookies.
   *
   * <p>The result is one-way: it reveals nothing about the signing key.
   *
   * @param label domain-separation string, so two purposes never share a derived key
   */
  public byte[] deriveSecret(String label) {
    try {
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(
          new javax.crypto.spec.SecretKeySpec(
              activeKey.getPrivateExponent().decode(), "HmacSHA256"));
      return mac.doFinal(label.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Unable to derive a secret from the signing key", e);
    }
  }

  // --- loading ---------------------------------------------------------------

  private static Map<String, RSAKey> loadKeys(
      AuthProperties.Jwt jwt, Map<String, String> aliasToKid) {

    String password = jwt.keystorePassword();
    if (password == null || password.isEmpty()) {
      throw new IllegalStateException(
          "auth.jwt.keystore-password is not set. Supply it from the environment "
              + "(PLATFORM_JWT_KEYSTORE_PASSWORD) or a secret manager - there is deliberately no "
              + "default.");
    }

    char[] secret = password.toCharArray();
    Map<String, RSAKey> keys = new LinkedHashMap<>();

    try (InputStream in = openKeystore(jwt.keystorePath())) {
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      keyStore.load(in, secret);

      for (String alias : Collections.list(keyStore.aliases())) {
        if (!keyStore.isKeyEntry(alias)) {
          continue;
        }
        Key key = keyStore.getKey(alias, secret);
        Certificate certificate = keyStore.getCertificate(alias);
        if (!(key instanceof RSAPrivateKey privateKey)
            || certificate == null
            || !(certificate.getPublicKey() instanceof RSAPublicKey publicKey)) {
          log.warn("Keystore alias '{}' is not an RSA key pair; skipping", alias);
          continue;
        }

        RSAKey unidentified =
            new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();

        // RFC 7638 thumbprint as the kid. Derived from the key itself rather
        // than from the alias, so the same key always advertises the same kid no
        // matter which keystore or environment it was loaded from - which is what
        // lets a consumer cache by kid across a redeploy.
        String kid = unidentified.computeThumbprint().toString();
        keys.put(kid, new RSAKey.Builder(unidentified).keyID(kid).build());
        aliasToKid.put(alias, kid);
      }
    } catch (IOException | GeneralSecurityException | JOSEException e) {
      throw new IllegalStateException(
          "Unable to load JWT signing keystore from '"
              + jwt.keystorePath()
              + "'. Generate one with infra/scripts/generate-keystore.sh and mount it; "
              + "auth-service will not start without it.",
          e);
    }

    if (keys.isEmpty()) {
      throw new IllegalStateException(
          "JWT keystore '" + jwt.keystorePath() + "' contains no RSA key pair.");
    }
    return Collections.unmodifiableMap(keys);
  }

  private static InputStream openKeystore(String location) throws IOException {
    if (location == null || location.isBlank()) {
      throw new IllegalStateException("auth.jwt.keystore-path is not set");
    }
    boolean prefixed = RESOURCE_PREFIXES.stream().anyMatch(location::startsWith);
    if (prefixed) {
      Resource resource = new DefaultResourceLoader().getResource(location);
      if (!resource.exists()) {
        throw new IOException("Keystore resource does not exist: " + location);
      }
      return resource.getInputStream();
    }
    // A bare path is a FILESYSTEM path. Spring's DefaultResourceLoader would
    // resolve it against the classpath, which silently finds nothing (or, worse,
    // finds a keystore that was accidentally packaged into the jar).
    Path path = Path.of(location);
    if (!Files.isReadable(path)) {
      throw new IOException("Keystore file is missing or unreadable: " + path.toAbsolutePath());
    }
    return Files.newInputStream(path);
  }

  private RSAKey selectActiveKey(String configured, Map<String, String> aliasToKid) {
    if (configured == null || configured.isBlank()) {
      if (keysByKid.size() > 1) {
        throw new IllegalStateException(
            "The keystore holds "
                + keysByKid.size()
                + " keys, so auth.jwt.active-kid must name the signer explicitly "
                + "(keystore alias or RFC 7638 thumbprint). Available aliases: "
                + aliasToKid.keySet());
      }
      return keysByKid.values().iterator().next();
    }

    // Accept the thumbprint directly...
    RSAKey byKid = keysByKid.get(configured);
    if (byKid != null) {
      return byKid;
    }
    // ...or the keystore alias, which is the value a human actually knows.
    String kid = aliasToKid.get(configured);
    if (kid != null) {
      return keysByKid.get(kid);
    }
    throw new IllegalStateException(
        "auth.jwt.active-kid='"
            + configured
            + "' matches no keystore alias "
            + aliasToKid.keySet()
            + " and no key thumbprint "
            + keysByKid.keySet());
  }
}
