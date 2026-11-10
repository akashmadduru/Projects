package com.acme.ecommerce.product.support;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * A real RSA key pair plus a token minter, for tests that need genuinely valid (and genuinely
 * invalid) tokens.
 *
 * <p>THE SAME PATTERN AS {@code com.acme.ecommerce.gateway.support.TestKeys}, deliberately
 * duplicated rather than shared. Promoting it to a library would mean publishing a token-minting
 * helper on the main classpath of every service that depends on that library - a class whose entire
 * purpose is forging platform-trusted credentials. Two ~150-line test helpers is the cheaper risk.
 *
 * <p>Deliberately a real signer rather than a mocked {@code JwtDecoder}. The behaviour under test -
 * signature verification against a JWKS document fetched over HTTP, with issuer/audience/timestamp
 * validation layered on - is precisely the part a mock would assume works. Mocking the decoder
 * would produce a suite that passes with the security wiring removed.
 *
 * <p>Claims mirror what auth-service actually issues (verified against a real token in Phase 1C):
 * single-valued {@code aud}, unprefixed {@code roles} array, {@code auth_method}, {@code email}.
 */
public final class TestKeys {

  /** Matches auth-service's `auth.jwt.issuer` default. */
  public static final String ISSUER = "https://auth.acme-ecommerce.internal";

  /** Matches auth-service's `auth.jwt.audiences` default. Single-valued, not an array. */
  public static final String AUDIENCE = "ecommerce-platform";

  private final RSAKey signingKey;

  private TestKeys(RSAKey signingKey) {
    this.signingKey = signingKey;
  }

  /** Generates a fresh 2048-bit RSA signing key with the given {@code kid}. */
  public static TestKeys generate(String keyId) {
    try {
      return new TestKeys(
          new RSAKeyGenerator(2048).keyID(keyId).algorithm(JWSAlgorithm.RS256).generate());
    } catch (Exception failure) {
      throw new IllegalStateException("Could not generate a test RSA key", failure);
    }
  }

  /**
   * The JWKS document as auth-service publishes it: PUBLIC KEY MATERIAL ONLY.
   *
   * <p>{@code toString(true)} is the same call auth-service's JwksController makes, and for the
   * same reason - the no-arg form serialises {@code d, p, q, dp, dq, qi} and would hand anyone who
   * fetched it the ability to mint tokens the whole platform trusts.
   */
  public String jwksJson() {
    return new JWKSet(List.of(signingKey.toPublicJWK())).toString(true);
  }

  /** A valid access token for a user with the given roles. */
  public String accessToken(String subject, String email, List<String> roles) {
    return token(
        subject, email, roles, ISSUER, AUDIENCE, Instant.now(), Instant.now().plusSeconds(600));
  }

  /** A token that is structurally perfect but expired well beyond the 30s skew tolerance. */
  public String expiredToken(String subject) {
    return token(
        subject,
        "expired@example.com",
        List.of("VIEWER"),
        ISSUER,
        AUDIENCE,
        Instant.now().minusSeconds(7200),
        Instant.now().minusSeconds(3600));
  }

  /** A correctly-signed token from a different (but syntactically valid) issuer. */
  public String wrongIssuerToken(String subject) {
    return token(
        subject,
        "user@example.com",
        List.of("VIEWER"),
        "https://evil.example.com",
        AUDIENCE,
        Instant.now(),
        Instant.now().plusSeconds(600));
  }

  /** A correctly-signed token minted for a different relying party. */
  public String wrongAudienceToken(String subject) {
    return token(
        subject,
        "user@example.com",
        List.of("VIEWER"),
        ISSUER,
        "some-other-platform",
        Instant.now(),
        Instant.now().plusSeconds(600));
  }

  private String token(
      String subject,
      String email,
      List<String> roles,
      String issuer,
      String audience,
      Instant issuedAt,
      Instant expiresAt) {

    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject(subject)
            .issuer(issuer)
            .audience(audience)
            .issueTime(Date.from(issuedAt))
            .notBeforeTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiresAt))
            .jwtID(UUID.randomUUID().toString())
            .claim("email", email)
            .claim("auth_method", "password")
            .claim("roles", roles)
            .build();

    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.getKeyID())
                .type(JOSEObjectType.JWT)
                .build(),
            claims);

    try {
      jwt.sign(new RSASSASigner(signingKey));
    } catch (Exception failure) {
      throw new IllegalStateException("Could not sign a test token", failure);
    }
    return jwt.serialize();
  }
}
