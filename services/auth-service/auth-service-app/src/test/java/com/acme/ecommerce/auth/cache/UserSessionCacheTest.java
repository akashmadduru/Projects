package com.acme.ecommerce.auth.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.auth.api.dto.AuthMethod;
import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.auth.domain.UserAccount;
import com.acme.ecommerce.auth.token.AuthenticatedPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link UserSessionCache}'s two contracts: the key/TTL shape it writes on success, and - the
 * single most important behaviour in this class - that it NEVER lets a Redis failure escape.
 */
@ExtendWith(MockitoExtension.class)
class UserSessionCacheTest {

  @Mock private StringRedisTemplate redis;
  @Mock private ValueOperations<String, String> valueOperations;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private UserSessionCache cache;

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UserSnapshot SNAPSHOT =
      new UserSnapshot(USER_ID, "user@example.com", "A User", Set.of("VIEWER"), "ACTIVE");

  @BeforeEach
  void setUp() {
    AuthProperties properties =
        new AuthProperties(
            new AuthProperties.Jwt(
                "https://auth.test.internal",
                java.util.List.of("ecommerce-platform"),
                Duration.ofMinutes(10),
                "irrelevant.p12",
                "irrelevant",
                ""),
            new AuthProperties.Refresh(
                Duration.ofDays(14), Duration.ofDays(30), Duration.ofSeconds(10)),
            new AuthProperties.Cookie("refresh_token", "/api/auth", true, "Lax", ""),
            new AuthProperties.Registration(false),
            new AuthProperties.Lockout(5, Duration.ofMinutes(1), Duration.ofMinutes(15)),
            new AuthProperties.RateLimit(true, 10, 60, Duration.ofMinutes(1), false, 50000),
            new AuthProperties.Oauth2("", "", java.util.List.of(), "", ""),
            new AuthProperties.Housekeeping(true, Duration.ofDays(30), Duration.ofHours(1)));
    cache = new UserSessionCache(redis, objectMapper, properties);
  }

  @Test
  @DisplayName("writes user-snapshot:{userId} as JSON with a TTL equal to the access-token TTL")
  void writesKeyAndTtlFromAccessTokenTtl() {
    when(redis.opsForValue()).thenReturn(valueOperations);

    cache.put(USER_ID, SNAPSHOT);

    verify(valueOperations)
        .set(eq("user-snapshot:" + USER_ID), any(String.class), eq(Duration.ofMinutes(10)));
  }

  @Test
  @DisplayName("FAILS OPEN: a Redis outage never propagates out of put()")
  void putNeverThrowsWhenRedisFails() {
    when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("connection refused"));

    assertThatCode(() -> cache.put(USER_ID, SNAPSHOT)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("FAILS OPEN: a failure from the SET command itself never propagates out of put()")
  void putNeverThrowsWhenSetFails() {
    when(redis.opsForValue()).thenReturn(valueOperations);
    org.mockito.Mockito.doThrow(new RedisConnectionFailureException("timed out"))
        .when(valueOperations)
        .set(any(), any(), any(Duration.class));

    assertThatCode(() -> cache.put(USER_ID, SNAPSHOT)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("UserSnapshot.from combines the principal's JWT-facing fields with the account's")
  void snapshotFromCombinesPrincipalAndAccount() {
    AuthenticatedPrincipal principal =
        new AuthenticatedPrincipal(
            USER_ID, "user@example.com", Set.of("VIEWER"), AuthMethod.PASSWORD);
    UserAccount account = UserAccount.create("user@example.com", "A User", true);

    UserSnapshot snapshot = UserSnapshot.from(principal, account);

    assertThat(snapshot.id()).isEqualTo(USER_ID);
    assertThat(snapshot.email()).isEqualTo("user@example.com");
    assertThat(snapshot.displayName()).isEqualTo("A User");
    assertThat(snapshot.roles()).containsExactly("VIEWER");
    assertThat(snapshot.status()).isEqualTo(account.getStatus().name());
  }
}
