package com.acme.ecommerce.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.gateway.config.GatewayProperties;
import com.acme.ecommerce.gateway.support.StubHttpServer;
import com.acme.ecommerce.gateway.support.TestKeys;
import com.nimbusds.jose.proc.SecurityContext;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * The JWKS caching contract, asserted against a real HTTP JWKS endpoint.
 *
 * <p>Each test here corresponds to a specific way the naive {@code
 * NimbusReactiveJwtDecoder.withJwkSetUri(...)} configuration hurts at an edge - a cached decoder is
 * easy to build and easy to build wrongly, and every one of these failure modes only shows up in
 * production, under load, during an incident.
 */
class JwksReactiveJwtDecoderFactoryTest {

  private static final String SUBJECT = "3f5c1c6a-8f1e-4f0a-9d3a-7d6a2f1b2c3d";

  @Test
  @DisplayName("accepts a token signed by a key from the JWKS document")
  void acceptsValidToken() {
    TestKeys keys = TestKeys.generate("kid-1");
    try (StubHttpServer jwks = StubHttpServer.start(keys::jwksJson)) {
      ReactiveJwtDecoder decoder = decoder(jwks, new Fixtures());

      StepVerifier.create(
              decoder.decode(
                  keys.accessToken(SUBJECT, "user@example.com", "password", List.of("VIEWER"))))
          .assertNext(
              jwt -> {
                assertThat(jwt.getSubject()).isEqualTo(SUBJECT);
                assertThat(jwt.getAudience()).containsExactly(TestKeys.AUDIENCE);
                assertThat(jwt.getClaimAsStringList("roles")).containsExactly("VIEWER");
                assertThat(jwt.getClaimAsString("auth_method")).isEqualTo("password");
              })
          .verifyComplete();
    }
  }

  @Test
  @DisplayName("rejects expired, wrong-issuer, wrong-audience and foreign-key tokens")
  void rejectsInvalidTokens() {
    TestKeys keys = TestKeys.generate("kid-1");
    TestKeys foreignKeys = TestKeys.generate("kid-1"); // same kid, different key material
    try (StubHttpServer jwks = StubHttpServer.start(keys::jwksJson)) {
      ReactiveJwtDecoder decoder = decoder(jwks, new Fixtures());

      // Signature is fine, timestamps are not: the single most common real failure.
      expectRejected(decoder, keys.expiredToken(SUBJECT));
      // A different issuer that signs its own tokens perfectly well.
      expectRejected(decoder, keys.wrongIssuerToken(SUBJECT));
      // THE replay case audience validation exists for: a genuine token from this issuer, minted
      // for someone else.
      expectRejected(decoder, keys.wrongAudienceToken(SUBJECT));
      // Right kid, wrong key - i.e. a forged token that guessed the key id.
      expectRejected(
          decoder,
          foreignKeys.accessToken(SUBJECT, "user@example.com", "password", List.of("VIEWER")));
    }
  }

  @Test
  @DisplayName("steady state performs no network call per request")
  void steadyStateMakesNoNetworkCalls() {
    TestKeys keys = TestKeys.generate("kid-1");
    try (StubHttpServer jwks = StubHttpServer.start(keys::jwksJson)) {
      Fixtures fixtures = new Fixtures();
      ReactiveJwtDecoder decoder = decoder(jwks, fixtures);
      String token = keys.accessToken(SUBJECT, "user@example.com", "password", List.of("VIEWER"));

      // Warm the cache.
      decoder.decode(token).block(Duration.ofSeconds(5));
      long afterWarmup = fixtures.source.fetchCount();

      for (int i = 0; i < 50; i++) {
        assertThat(decoder.decode(token).block(Duration.ofSeconds(5))).isNotNull();
      }

      // THE point of the refresh-ahead cache. Without it, every validation whose kid missed the
      // cache would be a synchronous HTTP call to auth-service - i.e. the gateway would DDoS the
      // service it depends on most, in proportion to its own success.
      assertThat(fixtures.source.fetchCount()).isEqualTo(afterWarmup);
    }
  }

  @Test
  @DisplayName("a burst carrying an unknown kid produces ONE forced refresh, not one per request")
  void unknownKidBurstIsRateLimited() {
    TestKeys published = TestKeys.generate("kid-1");
    TestKeys rotated = TestKeys.generate("kid-2");

    try (StubHttpServer jwks = StubHttpServer.start(published::jwksJson)) {
      Fixtures fixtures = new Fixtures();
      ReactiveJwtDecoder decoder = decoder(jwks, fixtures);

      // Warm the cache on the published key.
      decoder
          .decode(published.accessToken(SUBJECT, "u@example.com", "password", List.of("VIEWER")))
          .block(Duration.ofSeconds(5));
      long afterWarmup = fixtures.source.fetchCount();

      // 25 concurrent requests carrying a kid the cache has never seen - exactly what the first
      // seconds after a key rotation look like.
      String rotatedToken =
          rotated.accessToken(SUBJECT, "u@example.com", "password", List.of("VIEWER"));
      Flux.merge(
              IntStream.range(0, 25)
                  .mapToObj(
                      i ->
                          decoder
                              .decode(rotatedToken)
                              .onErrorResume(error -> reactor.core.publisher.Mono.empty()))
                  .toList())
          .blockLast(Duration.ofSeconds(20));

      // At most one extra fetch. Without the rate limiter this is 25 - and in production, with a
      // real request rate, it is however many requests arrive before the rotation propagates.
      assertThat(fixtures.source.fetchCount() - afterWarmup).isLessThanOrEqualTo(1);
    }
  }

  @Test
  @DisplayName("keeps validating tokens while the JWKS endpoint is down")
  void survivesAnAuthServiceOutage() throws InterruptedException {
    TestKeys keys = TestKeys.generate("kid-1");
    AtomicBoolean up = new AtomicBoolean(true);

    try (StubHttpServer jwks =
        StubHttpServer.start(
            () -> {
              if (!up.get()) {
                throw new IllegalStateException("auth-service is down");
              }
              return keys.jwksJson();
            })) {
      Fixtures fixtures = new Fixtures();
      // Deliberately tiny windows so the cached key set is stale within the test's lifetime and it
      // is the outage-tolerant layer - not the cache - that answers.
      //
      // NOTE Nimbus enforces rateLimit < ttl at build time (an IllegalStateException), which is
      // why these move together. That validation is welcome: the production values (15m / 30s)
      // would otherwise be easy to invert during a tuning session and the symptom would be a
      // startup failure nobody could place.
      // Nimbus also enforces refreshAhead + refreshTimeout <= ttl.
      fixtures.ttl = Duration.ofSeconds(3);
      fixtures.refreshTimeout = Duration.ofSeconds(1);
      fixtures.refreshAhead = Duration.ofSeconds(1);
      fixtures.rateLimit = Duration.ofMillis(500);
      ReactiveJwtDecoder decoder = decoder(jwks, fixtures);

      String token = keys.accessToken(SUBJECT, "user@example.com", "password", List.of("VIEWER"));
      assertThat(decoder.decode(token).block(Duration.ofSeconds(5))).isNotNull();

      up.set(false);
      // Past the 3s TTL: the cache alone can no longer answer.
      Thread.sleep(3500);

      // THE reason outage tolerance is configured at all: without it, auth-service restarting
      // would make every token in the fleet unverifiable and turn a single-service blip into a
      // total platform outage.
      Jwt duringOutage = decoder.decode(token).block(Duration.ofSeconds(10));
      assertThat(duringOutage).isNotNull();
      assertThat(duringOutage.getSubject()).isEqualTo(SUBJECT);
    }
  }

  @Test
  @DisplayName("does not touch the JWKS endpoint until the first token arrives")
  void doesNotFetchEagerlyAtConstruction() {
    TestKeys keys = TestKeys.generate("kid-1");
    try (StubHttpServer jwks = StubHttpServer.start(keys::jwksJson)) {
      Fixtures fixtures = new Fixtures();
      decoder(jwks, fixtures);

      // Lazy on purpose. An eager fetch would make the gateway's startup depend on auth-service
      // already being up, which is exactly the compose bring-up ordering the edge must not have.
      assertThat(fixtures.source.fetchCount()).isZero();
    }
  }

  private static void expectRejected(ReactiveJwtDecoder decoder, String token) {
    StepVerifier.create(decoder.decode(token)).expectError(JwtException.class).verify();
  }

  private static ReactiveJwtDecoder decoder(StubHttpServer jwks, Fixtures fixtures) {
    URI uri = URI.create(jwks.baseUrl() + "/.well-known/jwks.json");
    fixtures.source =
        new HttpJwkSetSource<>(() -> uri, Duration.ofSeconds(2), Duration.ofSeconds(3));
    return JwksReactiveJwtDecoderFactory.create(fixtures.source, fixtures.properties());
  }

  /** Mutable knobs for the individual scenarios, plus the source whose fetches are counted. */
  private static final class Fixtures {
    private HttpJwkSetSource<SecurityContext> source;
    private Duration ttl = Duration.ofMinutes(15);
    private Duration refreshTimeout = Duration.ofSeconds(5);
    private Duration refreshAhead = Duration.ofMinutes(2);
    private Duration rateLimit = Duration.ofSeconds(30);

    private GatewayProperties properties() {
      return new GatewayProperties(
          new GatewayProperties.Jwt(
              TestKeys.ISSUER, TestKeys.AUDIENCE, "AUTH-SERVICE", "/.well-known/jwks.json", ""),
          new GatewayProperties.Jwks(
              ttl,
              refreshTimeout,
              refreshAhead,
              rateLimit,
              Duration.ofHours(1),
              Duration.ofSeconds(2),
              Duration.ofSeconds(3)),
          new GatewayProperties.Cors(
              List.of("http://localhost:5173"),
              List.of("GET"),
              List.of("Authorization"),
              List.of("X-Correlation-Id"),
              Duration.ofHours(1)));
    }
  }
}
