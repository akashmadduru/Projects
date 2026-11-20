package com.acme.ecommerce.auth.cache;

import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.auth.metrics.AuthMetrics;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.common.security.AccessTokenDenyList;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Writes and reads the access-token deny-list entry {@code access-deny:{sub}} (ADR 0005).
 *
 * <p>The value is a cutoff in epoch seconds that only ever moves FORWARD: the write is one atomic
 * Lua script taking the max of the stored and the new cutoff, so a second disable, a demote after a
 * disable or a replica with a lagging clock can never bring revoked tokens back. Enabling a user
 * never deletes the key, for the same reason.
 *
 * <p>The write NEVER throws (a Redis outage must not fail the admin action; refresh-token
 * revocation in the database still holds). The read throws, so the validator can tell "absent" from
 * "unavailable".
 */
@Component
public class AccessTokenDenyStore {

  /** Both values are coerced with tonumber: Redis hands them over as strings. */
  private static final DefaultRedisScript<Long> FORWARD_ONLY =
      new DefaultRedisScript<>(
          "local cur = tonumber(redis.call('GET', KEYS[1])) or 0 "
              + "local v = math.max(cur, tonumber(ARGV[1])) "
              + "redis.call('SET', KEYS[1], v, 'EX', tonumber(ARGV[2])) "
              + "return v",
          Long.class);

  private static final Logger log = LoggerFactory.getLogger(AccessTokenDenyStore.class);

  private final StringRedisTemplate redis;
  private final TimeProvider timeProvider;
  private final AuthMetrics metrics;
  private final Duration ttl;

  public AccessTokenDenyStore(
      StringRedisTemplate redis,
      TimeProvider timeProvider,
      AuthMetrics metrics,
      AuthProperties properties) {
    this.redis = redis;
    this.timeProvider = timeProvider;
    this.metrics = metrics;
    this.ttl = properties.jwt().accessTokenTtl().plus(AccessTokenDenyList.MARGIN);
  }

  /** Revokes every access token of the user issued up to now. NEVER THROWS. */
  public void revokeIssuedUpTo(UUID userId) {
    try {
      long cutoff = timeProvider.now().getEpochSecond();
      redis.execute(
          FORWARD_ONLY,
          List.of(AccessTokenDenyList.key(userId.toString())),
          Long.toString(cutoff),
          Long.toString(ttl.toSeconds()));
    } catch (Exception e) {
      metrics.accessDenyWriteFailed();
      log.warn("Failed to write access-token deny entry for userId={}: {}", userId, e.toString());
    }
  }

  /**
   * The stored cutoff in epoch seconds, or {@code null} when there is none. Throws on a Redis
   * failure or an unreadable value; the caller decides the policy (fail-open, ADR 0005).
   */
  public Long cutoffOf(String sub) {
    String value = redis.opsForValue().get(AccessTokenDenyList.key(sub));
    return value == null ? null : Long.valueOf(value);
  }
}
