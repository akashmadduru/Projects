package com.acme.ecommerce.auth.cache;

import com.acme.ecommerce.auth.config.AuthProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes {@code user-snapshot:{userId}} to Redis on every token issuance ({@link
 * com.acme.ecommerce.auth.token.TokenService#issueTokenPair}).
 *
 * <p>FAIL OPEN, ALWAYS. This cache is a performance aid for a future consumer, not a dependency of
 * login/refresh - a Redis outage must never turn an otherwise-successful login into a 500. That
 * would make an optional cache a hard dependency, the same class of mistake the gateway's lazy JWKS
 * fetch and outage-tolerant token verification exist specifically to avoid elsewhere in this
 * platform. Every failure path here is caught, logged at WARN, and swallowed - never rethrown.
 */
@Component
public class UserSessionCache {

  private static final String KEY_PREFIX = "user-snapshot:";
  private static final Logger log = LoggerFactory.getLogger(UserSessionCache.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final Duration ttl;

  public UserSessionCache(
      StringRedisTemplate redis, ObjectMapper objectMapper, AuthProperties properties) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    // The SAME access-token TTL AccessTokenIssuer signs with - not a second,
    // independently-configured value. See AuthProperties.Jwt#accessTokenTtl.
    this.ttl = properties.jwt().accessTokenTtl();
  }

  /**
   * Writes the snapshot with a TTL equal to the access-token lifetime.
   *
   * <p>NEVER THROWS - see the class-level note.
   */
  public void put(UUID userId, UserSnapshot snapshot) {
    try {
      String json = objectMapper.writeValueAsString(snapshot);
      redis.opsForValue().set(KEY_PREFIX + userId, json, ttl);
    } catch (Exception e) {
      log.warn("Failed to write user-snapshot cache for userId={}: {}", userId, e.getMessage());
    }
  }
}
