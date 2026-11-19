package com.acme.ecommerce.auth.ratelimit;

import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.common.core.time.TimeProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counter shared by every replica through Redis.
 *
 * <p>The key is {@code key:(now / window)}. INCR and PEXPIRE run in one Lua call, so a key always
 * gets its TTL and concurrent callers cannot overshoot. A fixed window lets up to 2x the cap
 * through across a boundary; that is accepted for a soft control.
 *
 * <p>FAILS OPEN: any {@link RuntimeException} from Redis (down, timeout, script error) falls back
 * to a per-instance map with the same keys, so the limit still applies per replica and a request is
 * never failed. The WARN is throttled, and Redis is skipped for a few seconds after a failure so an
 * outage does not add latency to every request.
 */
@Component
public class SharedWindowCounter {

  private static final Logger log = LoggerFactory.getLogger(SharedWindowCounter.class);

  static final String WARN_MESSAGE = "Rate limiter Redis unavailable, using per-instance fallback";
  private static final Duration TTL_SLACK = Duration.ofSeconds(30);
  private static final long WARN_EVERY_NANOS = Duration.ofSeconds(30).toNanos();
  private static final long SKIP_REDIS_NANOS = Duration.ofSeconds(5).toNanos();

  private static final DefaultRedisScript<Long> INCR_WITH_TTL =
      new DefaultRedisScript<>(
          "local c = redis.call('INCR', KEYS[1]) "
              + "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
              + "return c",
          Long.class);

  private final StringRedisTemplate redis;
  private final TimeProvider timeProvider;
  private final int maxTracked;
  private final Map<String, AtomicLong> fallback = new ConcurrentHashMap<>();

  private volatile long skipRedisUntilNanos;
  private volatile long lastWarnNanos;
  private volatile boolean failedOnce;

  public SharedWindowCounter(
      StringRedisTemplate redis, TimeProvider timeProvider, AuthProperties properties) {
    this.redis = redis;
    this.timeProvider = timeProvider;
    this.maxTracked = properties.rateLimit().maxTrackedClients();
  }

  /** Increments the counter for the current window and returns the new count. Never throws. */
  public long increment(String key, Duration window) {
    String fullKey = key + ":" + (timeProvider.epochMilli() / window.toMillis());
    long now = System.nanoTime();
    if (!failedOnce || now - skipRedisUntilNanos >= 0) {
      try {
        Long count =
            redis.execute(
                INCR_WITH_TTL, List.of(fullKey), Long.toString(window.plus(TTL_SLACK).toMillis()));
        if (count == null) {
          throw new IllegalStateException("null script result");
        }
        return count;
      } catch (RuntimeException e) {
        noteFailure(now, e);
      }
    }
    return fallbackIncrement(fullKey);
  }

  private void noteFailure(long now, RuntimeException e) {
    skipRedisUntilNanos = now + SKIP_REDIS_NANOS;
    if (!failedOnce || now - lastWarnNanos >= WARN_EVERY_NANOS) {
      log.warn("{}: {}", WARN_MESSAGE, e.toString());
      lastWarnNanos = now;
    }
    failedOnce = true;
  }

  private long fallbackIncrement(String fullKey) {
    if (fallback.size() > maxTracked) {
      fallback.clear();
    }
    return fallback.computeIfAbsent(fullKey, k -> new AtomicLong()).incrementAndGet();
  }
}
