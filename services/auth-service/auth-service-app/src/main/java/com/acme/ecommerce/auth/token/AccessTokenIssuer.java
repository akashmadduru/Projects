package com.acme.ecommerce.auth.token;

import com.acme.ecommerce.auth.config.AuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds and signs the access token.
 *
 * <p>PACKAGE-PRIVATE ACCESS TO THE KEY. This is the only class that ever touches {@link
 * JwtKeyProvider#activeSigningKey()}, which is what makes "who can mint a token?" answerable by
 * reading one file.
 *
 * <p>THE CLAIM SET IS A CROSS-SERVICE CONTRACT. {@code roles} carries UNPREFIXED names because
 * {@code common-security}'s {@code RolesClaimAuthoritiesConverter} adds the {@code ROLE_} prefix
 * when it builds authorities; emitting prefixed names here would produce {@code ROLE_ROLE_ADMIN}
 * authorities and every {@code hasRole(...)} rule in the platform would silently stop matching.
 */
@Component
class AccessTokenIssuer {

  /**
   * {@code typ} header value.
   *
   * <p>RFC 9068 would have this be {@code at+jwt}, and that is the more correct answer in the
   * abstract. It is NOT used here because Spring Security's {@code NimbusJwtDecoder} - which the
   * gateway, this service and every future resource server use - ships with a type verifier that
   * accepts only {@code JWT} or an absent {@code typ}. Emitting {@code at+jwt} would mean every
   * consumer needs a custom verifier, and the first one to forget produces an outage that looks
   * like a signature problem.
   */
  private static final JOSEObjectType TOKEN_TYPE = JOSEObjectType.JWT;

  private final JwtKeyProvider keyProvider;
  private final AuthProperties.Jwt config;

  AccessTokenIssuer(JwtKeyProvider keyProvider, AuthProperties properties) {
    this.keyProvider = keyProvider;
    this.config = properties.jwt();
  }

  /** Access-token lifetime in seconds; surfaced to clients as {@code expiresIn}. */
  long ttlSeconds() {
    return config.accessTokenTtl().toSeconds();
  }

  /**
   * Signs an access token for {@code principal}.
   *
   * @param issuedAt the issuance instant, supplied by the caller so that the access token and its
   *     sibling refresh token share one consistent notion of "now"
   */
  String issue(AuthenticatedPrincipal principal, Instant issuedAt) {
    Instant expiresAt = issuedAt.plusSeconds(ttlSeconds());

    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(config.issuer())
            .subject(principal.userId().toString())
            .audience(config.audiences())
            .issueTime(Date.from(issuedAt))
            // nbf == iat: the token is valid immediately. Consumers apply their
            // own clock-skew tolerance (30-60s), so there is no need to
            // back-date this and thereby widen the window a stolen token is
            // usable in.
            .notBeforeTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiresAt))
            // A unique jti is what makes a future deny-list (or a replay
            // detector) possible without re-issuing every token first.
            .jwtID(UUID.randomUUID().toString())
            .claim("email", principal.email())
            .claim("roles", sortedRoles(principal))
            .claim("auth_method", principal.authMethod().claimValue())
            .build();

    JWSHeader header =
        new JWSHeader.Builder(JWSAlgorithm.RS256)
            // The kid is what lets a consumer pick the right key out of the JWKS
            // document during a rotation, instead of trying each in turn.
            .keyID(keyProvider.activeKid())
            .type(TOKEN_TYPE)
            .build();

    try {
      SignedJWT jwt = new SignedJWT(header, claims);
      jwt.sign(new RSASSASigner(keyProvider.activeSigningKey()));
      return jwt.serialize();
    } catch (JOSEException e) {
      // Signing can only fail for a structural reason (unusable key), which is a
      // configuration defect, not a request-level one.
      throw new IllegalStateException("Failed to sign access token", e);
    }
  }

  /** Sorted for a deterministic claim, which makes token comparison in tests meaningful. */
  private static List<String> sortedRoles(AuthenticatedPrincipal principal) {
    return principal.roles().stream().sorted().toList();
  }
}
