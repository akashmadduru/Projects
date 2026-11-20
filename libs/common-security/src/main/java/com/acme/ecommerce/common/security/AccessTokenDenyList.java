package com.acme.ecommerce.common.security;

import java.time.Duration;
import java.time.Instant;

/**
 * The one shared definition of the access-token deny-list entry (ADR 0005).
 *
 * <p>The entry is {@code access-deny:{sub}} and its value is a cutoff in epoch seconds: every
 * access token for that subject with {@code iat <= cutoff} is revoked. Written by auth-service on
 * disable and demote, read by auth-service and the gateway on every authenticated request.
 */
public final class AccessTokenDenyList {

  public static final String KEY_PREFIX = "access-deny:";

  /** Added to the access-token TTL: covers the consumers' 30 s {@code exp} skew plus latency. */
  public static final Duration MARGIN = Duration.ofSeconds(60);

  private AccessTokenDenyList() {}

  /** The Redis key for a subject. A null or blank subject is never turned into a key. */
  public static String key(String sub) {
    if (sub == null || sub.isBlank()) {
      throw new IllegalArgumentException("sub must not be null or blank");
    }
    return KEY_PREFIX + sub;
  }

  /** True when the token was issued at or before the cutoff, or carries no {@code iat}. */
  public static boolean revoked(Instant iat, long cutoffEpochSec) {
    return iat == null || iat.getEpochSecond() <= cutoffEpochSec;
  }
}
