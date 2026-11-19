package com.acme.ecommerce.auth.support;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Empties the shared test Redis so counters from other tests cannot leak into assertions. */
@Component
public class RedisReset {

  private final StringRedisTemplate redis;

  public RedisReset(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void flush() {
    redis.execute(
        connection -> {
          connection.serverCommands().flushAll();
          return null;
        },
        true);
  }
}
