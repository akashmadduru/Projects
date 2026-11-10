package com.acme.ecommerce.auth.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Introspection result for {@code POST /api/auth/validate}.
 *
 * <p>Shaped after RFC 7662 token introspection without claiming to implement it: {@code valid} is
 * the analogue of {@code active}, and the remaining members mirror the claims a consumer would
 * otherwise have to parse itself.
 *
 * @param valid whether the token is currently usable
 * @param userId the {@code sub} claim
 * @param email the {@code email} claim
 * @param roles unprefixed role names from the {@code roles} claim
 * @param authMethod the {@code auth_method} claim
 * @param expiresAt the {@code exp} claim
 */
public record ValidateResponse(
    boolean valid,
    UUID userId,
    String email,
    List<String> roles,
    AuthMethod authMethod,
    Instant expiresAt) {

  public ValidateResponse {
    roles = roles == null ? List.of() : List.copyOf(roles);
  }

  /** The response for a token that failed validation. Carries no principal detail whatsoever. */
  public static ValidateResponse invalid() {
    return new ValidateResponse(false, null, null, List.of(), null, null);
  }
}
