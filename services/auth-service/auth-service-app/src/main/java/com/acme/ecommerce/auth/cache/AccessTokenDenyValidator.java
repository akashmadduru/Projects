package com.acme.ecommerce.auth.cache;

import com.acme.ecommerce.auth.metrics.AuthMetrics;
import com.acme.ecommerce.common.security.AccessTokenDenyList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Rejects an access token whose subject has a deny-list cutoff at or after the token's {@code iat}.
 *
 * <p>A failure becomes a {@code JwtValidationException}, a {@code BadJwtException}, which Spring
 * maps to 401. FAIL OPEN: if Redis cannot be read the token passes (WARN plus a counter), because
 * Redis is a soft dependency platform-wide (ADR 0005).
 */
@Component
public class AccessTokenDenyValidator implements OAuth2TokenValidator<Jwt> {

  private static final Logger log = LoggerFactory.getLogger(AccessTokenDenyValidator.class);
  private static final OAuth2Error REVOKED =
      new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The token has been revoked", null);
  private static final OAuth2Error NO_SUBJECT =
      new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The token has no subject", null);

  private final AccessTokenDenyStore store;
  private final AuthMetrics metrics;

  public AccessTokenDenyValidator(AccessTokenDenyStore store, AuthMetrics metrics) {
    this.store = store;
    this.metrics = metrics;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    String sub = token.getSubject();
    if (sub == null || sub.isBlank()) {
      return OAuth2TokenValidatorResult.failure(NO_SUBJECT);
    }
    Long cutoff;
    try {
      cutoff = store.cutoffOf(sub);
    } catch (Exception e) {
      metrics.accessDenyCheckUnavailable();
      log.warn("Access-token deny-list unavailable, failing open: {}", e.toString());
      return OAuth2TokenValidatorResult.success();
    }
    if (cutoff != null && AccessTokenDenyList.revoked(token.getIssuedAt(), cutoff)) {
      metrics.accessDenyRejected();
      return OAuth2TokenValidatorResult.failure(REVOKED);
    }
    return OAuth2TokenValidatorResult.success();
  }
}
