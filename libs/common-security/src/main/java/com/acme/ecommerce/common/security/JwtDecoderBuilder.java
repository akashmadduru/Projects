package com.acme.ecommerce.common.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds a JWKS-backed {@link JwtDecoder} with the validation every resource server in this
 * platform must apply.
 *
 * <p>Spring's defaults validate signature and expiry. They do NOT validate audience unless asked,
 * which is the gap that lets a token minted for service A be replayed against service B. This
 * builder makes audience validation a first-class, hard-to-forget option.
 *
 * <p>Signing keys are fetched from the issuer's JWKS endpoint and cached by {@link
 * NimbusJwtDecoder}, so key rotation is picked up without redeploying - never pin a public key into
 * configuration.
 *
 * <p>Typical use in a service's SecurityConfig:
 *
 * <pre>{@code
 * @Bean
 * JwtDecoder jwtDecoder(@Value("${platform.security.jwks-uri}") String jwksUri) {
 *   return JwtDecoderBuilder.withJwkSetUri(jwksUri)
 *       .issuer("https://auth.acme-ecommerce.internal")
 *       .audience("product-service")
 *       .build();
 * }
 * }</pre>
 */
public final class JwtDecoderBuilder {

  /**
   * Tolerance for clock skew between the issuer and this service. 30s is enough for normal NTP
   * drift without meaningfully extending a token's usable life.
   */
  private static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(30);

  private final String jwkSetUri;
  private final String issuerUri;
  private String expectedIssuer;
  private String expectedAudience;
  private Duration clockSkew = DEFAULT_CLOCK_SKEW;

  private JwtDecoderBuilder(String jwkSetUri, String issuerUri) {
    this.jwkSetUri = jwkSetUri;
    this.issuerUri = issuerUri;
  }

  /** Builds a decoder against an explicit JWKS endpoint. */
  public static JwtDecoderBuilder withJwkSetUri(String jwkSetUri) {
    return new JwtDecoderBuilder(Objects.requireNonNull(jwkSetUri, "jwkSetUri"), null);
  }

  /**
   * Builds a decoder by discovering the JWKS endpoint from the issuer's OIDC metadata.
   *
   * <p>Convenient, but it makes startup depend on the issuer being reachable. Prefer {@link
   * #withJwkSetUri(String)} for services that must start independently of auth-service.
   */
  public static JwtDecoderBuilder withIssuerLocation(String issuerUri) {
    return new JwtDecoderBuilder(null, Objects.requireNonNull(issuerUri, "issuerUri"));
  }

  /** Rejects tokens whose {@code iss} claim does not match exactly. */
  public JwtDecoderBuilder issuer(String issuer) {
    this.expectedIssuer = issuer;
    return this;
  }

  /**
   * Rejects tokens whose {@code aud} claim does not contain this value.
   *
   * <p>Strongly recommended for every resource server: without it, any token this issuer ever
   * minted is accepted here.
   */
  public JwtDecoderBuilder audience(String audience) {
    this.expectedAudience = audience;
    return this;
  }

  /** Overrides the default 30s clock-skew tolerance. */
  public JwtDecoderBuilder clockSkew(Duration clockSkew) {
    this.clockSkew = Objects.requireNonNull(clockSkew, "clockSkew");
    return this;
  }

  /** Builds the configured decoder. */
  public JwtDecoder build() {
    JwtDecoder decoder =
        jwkSetUri != null
            ? NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
            : JwtDecoders.fromIssuerLocation(issuerUri);

    OAuth2TokenValidator<Jwt> validator = buildValidator();
    if (decoder instanceof NimbusJwtDecoder nimbusJwtDecoder) {
      nimbusJwtDecoder.setJwtValidator(validator);
    }
    return decoder;
  }

  private OAuth2TokenValidator<Jwt> buildValidator() {
    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
    validators.add(new JwtTimestampValidator(clockSkew));

    if (expectedIssuer != null && !expectedIssuer.isBlank()) {
      validators.add(JwtValidators.createDefaultWithIssuer(expectedIssuer));
    }
    if (expectedAudience != null && !expectedAudience.isBlank()) {
      validators.add(new AudienceValidator(expectedAudience));
    }
    return new DelegatingOAuth2TokenValidator<>(validators);
  }
}
