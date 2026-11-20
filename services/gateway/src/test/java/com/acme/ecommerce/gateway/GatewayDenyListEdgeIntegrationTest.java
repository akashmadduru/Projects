package com.acme.ecommerce.gateway;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.gateway.support.StubHttpServer;
import com.acme.ecommerce.gateway.support.StubServiceInstances;
import com.acme.ecommerce.gateway.support.TestKeys;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * G2: the deny-list through the real filter chain. A revoked token must come back as the 401
 * problem document (BadJwtException to InvalidBearerTokenException to the entry point), not a 5xx.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayDenyListEdgeIntegrationTest.StubDownstreamConfiguration.class)
class GatewayDenyListEdgeIntegrationTest {

  private static final String SUBJECT = "3f5c1c6a-8f1e-4f0a-9d3a-7d6a2f1b2c3d";
  private static final String KEY = "access-deny:" + SUBJECT;

  private static final TestKeys KEYS = TestKeys.generate("gateway-deny-kid");
  private static final StubHttpServer DOWNSTREAM = StubHttpServer.start(KEYS::jwksJson);

  @LocalServerPort private int port;

  @MockitoBean private ReactiveStringRedisTemplate redis;

  @SuppressWarnings("unchecked")
  private final ReactiveValueOperations<String, String> ops =
      Mockito.mock(ReactiveValueOperations.class);

  private WebTestClient client;

  @DynamicPropertySource
  static void gatewayProperties(DynamicPropertyRegistry registry) {
    registry.add("test.downstream.port", DOWNSTREAM::port);
    registry.add("gateway.jwt.jwks-uri", () -> DOWNSTREAM.baseUrl() + "/.well-known/jwks.json");
    registry.add("eureka.client.enabled", () -> "false");
    // The limiter shares the mocked Redis template; it has its own tests.
    registry.add("gateway.rate-limit.enabled", () -> "false");
  }

  @AfterAll
  static void stopDownstream() {
    DOWNSTREAM.close();
  }

  @BeforeEach
  void setUp() {
    DOWNSTREAM.setAvailable(true);
    when(redis.opsForValue()).thenReturn(ops);
    client =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
  }

  private WebTestClient.ResponseSpec me(Instant iat) {
    return client
        .get()
        .uri("/api/auth/me")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + KEYS.accessTokenIssuedAt(SUBJECT, iat))
        .exchange();
  }

  @Test
  @DisplayName("a token issued before the cutoff is 401 with the problem body (AC-13, AC-14)")
  void revokedTokenIsUnauthorized() {
    Instant now = Instant.now();
    when(ops.get(KEY)).thenReturn(Mono.just(Long.toString(now.getEpochSecond())));

    me(now.minusSeconds(30))
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("UNAUTHENTICATED")
        .jsonPath("$.status")
        .isEqualTo(401);

    // AC-13: the entry read is access-deny:{sub}, holding the epoch-seconds cutoff.
    verify(ops).get(KEY);
  }

  @Test
  @DisplayName("a token issued after the cutoff is 200 (AC-15)")
  void tokenAfterCutoffIsOk() {
    Instant now = Instant.now();
    when(ops.get(anyString())).thenReturn(Mono.just(Long.toString(now.getEpochSecond() - 100)));

    me(now).expectStatus().isOk();
  }

  @Test
  @DisplayName("Redis throwing fails open with 200 (AC-17)")
  void redisDownFailsOpen() {
    when(ops.get(anyString())).thenReturn(Mono.error(new RedisConnectionFailureException("down")));

    me(Instant.now()).expectStatus().isOk();
  }

  @TestConfiguration(proxyBeanMethods = false)
  @LoadBalancerClients({
    @LoadBalancerClient(name = "AUTH-SERVICE", configuration = AuthServiceInstances.class),
    @LoadBalancerClient(name = "PRODUCT-SERVICE", configuration = ProductServiceInstances.class)
  })
  static class StubDownstreamConfiguration {}

  static class AuthServiceInstances {
    @Bean
    ServiceInstanceListSupplier authServiceInstanceListSupplier(Environment environment) {
      int port = Integer.parseInt(environment.getRequiredProperty("test.downstream.port"));
      return StubServiceInstances.at("AUTH-SERVICE", port);
    }
  }

  static class ProductServiceInstances {
    @Bean
    ServiceInstanceListSupplier productServiceInstanceListSupplier() {
      return StubServiceInstances.none("PRODUCT-SERVICE");
    }
  }
}
