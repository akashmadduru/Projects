package com.acme.ecommerce.common.security;

import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects a JWT whose {@code aud} claim does not contain the expected audience.
 *
 * <p>This closes a real attack path: without audience validation, a token legitimately issued for a
 * low-privilege service can be replayed against a high-privilege one, because both trust the same
 * issuer and the same signing key. The {@code aud} claim is what scopes a token to one recipient.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

  private final String expectedAudience;

  public AudienceValidator(String expectedAudience) {
    this.expectedAudience = Objects.requireNonNull(expectedAudience, "expectedAudience");
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    List<String> audiences = token.getAudience();
    if (audiences != null && audiences.contains(expectedAudience)) {
      return OAuth2TokenValidatorResult.success();
    }
    // The error description deliberately does not echo the token's actual audience back to the
    // caller; that would leak the names of other services to an attacker probing with a stolen
    // token.
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN,
            "The required audience is missing",
            "https://datatracker.ietf.org/doc/html/rfc9068#section-3"));
  }
}
