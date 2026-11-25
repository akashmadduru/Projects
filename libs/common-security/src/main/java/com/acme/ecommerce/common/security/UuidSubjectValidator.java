package com.acme.ecommerce.common.security;

import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects a JWT whose {@code sub} claim is missing or not a UUID.
 *
 * <p>auth-service only mints user tokens, and their subject is always the user id. Controllers
 * across the platform call {@code UUID.fromString(jwt.getSubject())}; without this check a token
 * with any other subject (signed by a key we trust, so only possible through a misconfigured
 * issuer) reached those controllers and failed with 500. Rejecting it here turns that into the
 * resource server's normal 401 (TD-SEC-12).
 */
public class UuidSubjectValidator implements OAuth2TokenValidator<Jwt> {

  private static final OAuth2Error INVALID_SUBJECT =
      new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The subject is not a user id", null);

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    String sub = token.getSubject();
    if (sub == null || sub.length() != 36) {
      return OAuth2TokenValidatorResult.failure(INVALID_SUBJECT);
    }
    try {
      UUID.fromString(sub);
      return OAuth2TokenValidatorResult.success();
    } catch (IllegalArgumentException e) {
      return OAuth2TokenValidatorResult.failure(INVALID_SUBJECT);
    }
  }
}
