package com.acme.ecommerce.auth.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.auth.metrics.AuthMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class AccessTokenDenyValidatorTest {

  private static final String SUB = "3f5c1c6a-8f1e-4f0a-9d3a-7d6a2f1b2c3d";
  private static final long CUTOFF = 1_800_000_000L;

  private final AccessTokenDenyStore store = mock(AccessTokenDenyStore.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final AccessTokenDenyValidator validator =
      new AccessTokenDenyValidator(store, new AuthMetrics(meters));

  private static Jwt jwt(String sub, Instant iat) {
    Jwt.Builder b =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .expiresAt(iat.plusSeconds(600))
            .issuedAt(iat);
    if (sub != null) {
      b.subject(sub);
    } else {
      b.claim("x", "y");
    }
    return b.build();
  }

  @Test
  @DisplayName("a token issued at or before the cutoff fails with invalid_token")
  void revokedFails() {
    when(store.cutoffOf(SUB)).thenReturn(CUTOFF);

    OAuth2TokenValidatorResult result =
        validator.validate(jwt(SUB, Instant.ofEpochSecond(CUTOFF - 5)));

    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).extracting("errorCode").containsExactly("invalid_token");
    assertThat(meters.counter("auth.access_deny.rejected").count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("no entry means success")
  void absentSucceeds() {
    when(store.cutoffOf(SUB)).thenReturn(null);
    assertThat(validator.validate(jwt(SUB, Instant.ofEpochSecond(CUTOFF))).hasErrors()).isFalse();
  }

  @Test
  @DisplayName("an iat after the cutoff means success")
  void issuedAfterCutoffSucceeds() {
    when(store.cutoffOf(SUB)).thenReturn(CUTOFF);
    assertThat(validator.validate(jwt(SUB, Instant.ofEpochSecond(CUTOFF + 1))).hasErrors())
        .isFalse();
  }

  @Test
  @DisplayName("a Redis failure fails open and counts it (AC-17)")
  void redisErrorFailsOpen() {
    when(store.cutoffOf(SUB)).thenThrow(new RedisConnectionFailureException("down"));

    assertThat(validator.validate(jwt(SUB, Instant.ofEpochSecond(CUTOFF - 5))).hasErrors())
        .isFalse();
    assertThat(meters.counter("auth.access_deny.check.unavailable").count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("a missing or blank subject fails without touching Redis")
  void nullSubFailsWithoutLookup() {
    assertThat(validator.validate(jwt(null, Instant.ofEpochSecond(CUTOFF))).hasErrors()).isTrue();
    assertThat(validator.validate(jwt(" ", Instant.ofEpochSecond(CUTOFF))).hasErrors()).isTrue();
    verifyNoInteractions(store);
  }
}
