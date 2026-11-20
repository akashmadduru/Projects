package com.acme.ecommerce.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** G1: the deny-list wrapper in isolation. */
class DenyListReactiveJwtDecoderTest {

  private static final String SUB = "3f5c1c6a-8f1e-4f0a-9d3a-7d6a2f1b2c3d";
  private static final String KEY = "access-deny:" + SUB;
  private static final long CUTOFF = 1_800_000_000L;

  private final ReactiveJwtDecoder delegate = mock(ReactiveJwtDecoder.class);
  private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final DenyListReactiveJwtDecoder decoder =
      new DenyListReactiveJwtDecoder(delegate, redis, meters);

  private Jwt decoded(String sub, long iat) {
    Jwt.Builder b =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .issuedAt(Instant.ofEpochSecond(iat))
            .expiresAt(Instant.ofEpochSecond(iat + 600));
    if (sub != null) {
      b.subject(sub);
    } else {
      b.claim("x", "y");
    }
    Jwt jwt = b.build();
    when(delegate.decode("t")).thenReturn(Mono.just(jwt));
    when(redis.opsForValue()).thenReturn(ops);
    return jwt;
  }

  @Test
  @DisplayName("a token issued at or before the cutoff fails with BadJwtException")
  void revokedIsBadJwt() {
    decoded(SUB, CUTOFF - 5);
    when(ops.get(KEY)).thenReturn(Mono.just(Long.toString(CUTOFF)));

    StepVerifier.create(decoder.decode("t")).expectError(BadJwtException.class).verify();
    assertThat(meters.counter("gateway.access_deny.rejected").count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("no entry passes the token through")
  void absentPasses() {
    Jwt jwt = decoded(SUB, CUTOFF);
    when(ops.get(KEY)).thenReturn(Mono.empty());

    StepVerifier.create(decoder.decode("t")).expectNext(jwt).verifyComplete();
  }

  @Test
  @DisplayName("an iat after the cutoff passes")
  void issuedAfterCutoffPasses() {
    Jwt jwt = decoded(SUB, CUTOFF + 1);
    when(ops.get(KEY)).thenReturn(Mono.just(Long.toString(CUTOFF)));

    StepVerifier.create(decoder.decode("t")).expectNext(jwt).verifyComplete();
  }

  @Test
  @DisplayName("a Redis error fails open and counts it (AC-17)")
  void redisErrorFailsOpen() {
    Jwt jwt = decoded(SUB, CUTOFF - 5);
    when(ops.get(KEY)).thenReturn(Mono.error(new RedisConnectionFailureException("down")));

    StepVerifier.create(decoder.decode("t")).expectNext(jwt).verifyComplete();
    assertThat(meters.counter("gateway.access_deny.check.unavailable").count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("a missing subject is BadJwtException and Redis is never consulted")
  void nullSubIsBadJwtWithoutLookup() {
    decoded(null, CUTOFF);

    StepVerifier.create(decoder.decode("t")).expectError(BadJwtException.class).verify();
    verify(ops, never()).get(anyString());
    verifyNoInteractions(ops);
  }
}
