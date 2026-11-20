package com.acme.ecommerce.auth.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.auth.metrics.AuthMetrics;
import com.acme.ecommerce.common.core.time.FixedTimeProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** The deny entry against a real Redis: value, TTL, forward-only write, and fail-open write. */
class AccessTokenDenyStoreTest {

  @SuppressWarnings("resource")
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

  private static LettuceConnectionFactory factory;
  private static StringRedisTemplate redis;

  private final FixedTimeProvider clock =
      new FixedTimeProvider(Instant.ofEpochSecond(1_800_000_000L));

  @BeforeAll
  static void start() {
    REDIS.start();
    factory = connect(REDIS.getHost(), REDIS.getMappedPort(6379));
    redis = new StringRedisTemplate(factory);
    redis.afterPropertiesSet();
  }

  @AfterAll
  static void stop() {
    factory.destroy();
    REDIS.stop();
  }

  private static LettuceConnectionFactory connect(String host, int port) {
    LettuceConnectionFactory f =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(host, port),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
    f.afterPropertiesSet();
    return f;
  }

  static AuthProperties properties() {
    return new AuthProperties(
        new AuthProperties.Jwt(
            "https://auth.test.internal",
            List.of("ecommerce-platform"),
            Duration.ofMinutes(10),
            "irrelevant.p12",
            "irrelevant",
            ""),
        new AuthProperties.Refresh(
            Duration.ofDays(14), Duration.ofDays(30), Duration.ofSeconds(10)),
        new AuthProperties.Cookie("refresh_token", "/api/auth", true, "Lax", ""),
        new AuthProperties.Registration(false),
        new AuthProperties.Lockout(5, Duration.ofMinutes(1), Duration.ofMinutes(15)),
        new AuthProperties.RateLimit(true, 10, 60, Duration.ofMinutes(1), 50000),
        new AuthProperties.Oauth2("", "", List.of(), "", ""),
        new AuthProperties.Housekeeping(true, Duration.ofDays(30), Duration.ofHours(1)),
        new AuthProperties.Storefront("http://localhost:3000"));
  }

  private AccessTokenDenyStore store(StringRedisTemplate template, SimpleMeterRegistry meters) {
    return new AccessTokenDenyStore(template, clock, new AuthMetrics(meters), properties());
  }

  private static String key(UUID id) {
    return "access-deny:" + id;
  }

  @Test
  @DisplayName("stores the cutoff as epoch seconds with a TTL in [600, 660] (AC-13, AC-16)")
  void storesCutoffWithTtl() {
    UUID id = UUID.randomUUID();
    store(redis, new SimpleMeterRegistry()).revokeIssuedUpTo(id);

    assertThat(redis.opsForValue().get(key(id))).isEqualTo("1800000000");
    Long ttl = redis.getExpire(key(id), TimeUnit.SECONDS);
    assertThat(ttl).isBetween(600L, 660L);
  }

  @Test
  @DisplayName("a second write with a lower cutoff leaves the value unchanged (forward-only)")
  void lowerCutoffDoesNotMoveBack() {
    UUID id = UUID.randomUUID();
    AccessTokenDenyStore store = store(redis, new SimpleMeterRegistry());
    store.revokeIssuedUpTo(id);
    clock.advanceBy(Duration.ofSeconds(-100));
    store.revokeIssuedUpTo(id);

    assertThat(redis.opsForValue().get(key(id))).isEqualTo("1800000000");
    clock.advanceBy(Duration.ofSeconds(100));
  }

  @Test
  @DisplayName("a higher cutoff advances the value: disable then demote")
  void higherCutoffAdvances() {
    UUID id = UUID.randomUUID();
    AccessTokenDenyStore store = store(redis, new SimpleMeterRegistry());
    store.revokeIssuedUpTo(id);
    clock.advanceBy(Duration.ofSeconds(30));
    store.revokeIssuedUpTo(id);

    assertThat(redis.opsForValue().get(key(id))).isEqualTo("1800000030");
    assertThat(store.cutoffOf(id.toString())).isEqualTo(1_800_000_030L);
    clock.advanceBy(Duration.ofSeconds(-30));
  }

  @Test
  @DisplayName("an absent entry reads as null")
  void absentIsNull() {
    assertThat(store(redis, new SimpleMeterRegistry()).cutoffOf(UUID.randomUUID().toString()))
        .isNull();
  }

  @Test
  @DisplayName("a write with Redis down does not throw and counts the failure (AC-17)")
  void redisDownWriteDoesNotThrow() {
    LettuceConnectionFactory dead = connect("127.0.0.1", 1);
    try {
      StringRedisTemplate template = new StringRedisTemplate(dead);
      template.afterPropertiesSet();
      SimpleMeterRegistry meters = new SimpleMeterRegistry();

      assertThatCode(() -> store(template, meters).revokeIssuedUpTo(UUID.randomUUID()))
          .doesNotThrowAnyException();
      assertThat(meters.counter("auth.access_deny.write.failed").count()).isEqualTo(1.0);
    } finally {
      dead.destroy();
    }
  }
}
