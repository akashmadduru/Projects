package com.acme.ecommerce.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.gateway.support.StubHttpServer;
import com.acme.ecommerce.gateway.support.StubServiceInstances;
import com.acme.ecommerce.gateway.support.TestKeys;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
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
 * AC-4, AC-5, AC-10(a)(b): client -> real Netty gateway -> real filter chain -> echo stub. Nothing
 * in the filter chain is mocked; only Redis (so 429 is reachable) and Eureka are replaced.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayClientIpIntegrationTest.StubDownstreamConfiguration.class)
class GatewayClientIpIntegrationTest {

  private static final String HEADER_KEY = "\"x-internal-client-ip\":";
  private static final TestKeys KEYS = TestKeys.generate("gateway-clientip-kid");
  private static final StubHttpServer DOWNSTREAM = StubHttpServer.start(KEYS::jwksJson);

  @LocalServerPort private int port;

  @MockitoBean private ReactiveStringRedisTemplate redis;

  private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
  private WebTestClient client;

  @DynamicPropertySource
  static void gatewayProperties(DynamicPropertyRegistry registry) {
    registry.add("test.downstream.port", DOWNSTREAM::port);
    registry.add("gateway.jwt.jwks-uri", () -> DOWNSTREAM.baseUrl() + "/.well-known/jwks.json");
    registry.add("eureka.client.enabled", () -> "false");
    registry.add("gateway.rate-limit.trusted-proxy-hops", () -> 1);
    registry.add("gateway.rate-limit.writes-per-minute", () -> 2);
    registry.add("gateway.rate-limit.reads-per-minute", () -> 1000);
  }

  @AfterAll
  static void stopDownstream() {
    DOWNSTREAM.close();
  }

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    ReactiveValueOperations<String, String> ops = mock(ReactiveValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.increment(anyString()))
        .thenAnswer(
            inv ->
                Mono.just(
                    counters
                        .computeIfAbsent(inv.getArgument(0), k -> new AtomicLong())
                        .incrementAndGet()));
    when(redis.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    client =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
  }

  private static String bearer(String subject) {
    return "Bearer " + KEYS.accessToken(subject, "u@example.com", "google", List.of("VIEWER"));
  }

  private String echoedIpHeader(WebTestClient.RequestHeadersSpec<?> request) {
    String body =
        request
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();
    assertThat(body).isNotNull();
    int at = body.indexOf(HEADER_KEY);
    assertThat(at).as("header echoed in %s", body).isGreaterThanOrEqualTo(0);
    assertThat(body.indexOf(HEADER_KEY, at + 1)).as("single header entry").isNegative();
    int start = at + HEADER_KEY.length() + 1;
    return body.substring(start, body.indexOf('"', start));
  }

  @Test
  @DisplayName("AC-10(a): a spoofed XFF entry cannot change the IP; the N-th from the right wins")
  void spoofedXff() {
    assertThat(
            echoedIpHeader(
                client
                    .get()
                    .uri("/api/products")
                    .header("X-Forwarded-For", "9.9.9.9, 203.0.113.7")))
        .isEqualTo("203.0.113.7");
  }

  @Test
  @DisplayName("AC-10(a): with two XFF field lines the real entry is the last of the joined list")
  void twoXffLines() {
    assertThat(
            echoedIpHeader(
                client
                    .get()
                    .uri("/api/products")
                    .header("X-Forwarded-For", "6.6.6.6")
                    .header("X-Forwarded-For", "9.9.9.9, 203.0.113.11")))
        .isEqualTo("203.0.113.11");
  }

  @Test
  @DisplayName("AC-4/AC-10(b): a spoofed X-Internal-Client-Ip never reaches the downstream")
  void spoofedHeaderOnPublicAuthenticatedAndRateLimitedRoutes() {
    String subject = UUID.randomUUID().toString();
    // public + rate-limited (anonymous ip key)
    assertThat(
            echoedIpHeader(
                client
                    .get()
                    .uri("/api/products")
                    .header("X-Internal-Client-Ip", "6.6.6.6")
                    .header("x-internal-client-ip", "7.7.7.7")
                    .header("X-INTERNAL-CLIENT-IP", "8.8.8.8, 9.9.9.9")
                    .header("X-Forwarded-For", "1.1.1.1, 203.0.113.8")))
        .isEqualTo("203.0.113.8");
    // public, not rate-limited (auth is skipped by the limiter)
    assertThat(
            echoedIpHeader(
                client
                    .post()
                    .uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .header("x-Internal-Client-IP", "6.6.6.6")
                    .header("X-Forwarded-For", "5.5.5.5, 203.0.113.9")))
        .isEqualTo("203.0.113.9");
    // authenticated + rate-limited (subject key)
    assertThat(
            echoedIpHeader(
                client
                    .get()
                    .uri("/api/users/me")
                    .header(HttpHeaders.AUTHORIZATION, bearer(subject))
                    .header("X-Internal-Client-Ip", "6.6.6.6")
                    .header("x-internal-client-ip", "7.7.7.7")
                    .header("X-Forwarded-For", "5.5.5.5, 203.0.113.10")))
        .isEqualTo("203.0.113.10");
    // no XFF at all: the peer (the test client, loopback), never the spoofed value
    assertThat(
            echoedIpHeader(
                client.get().uri("/api/products").header("X-Internal-Client-Ip", "6.6.6.6")))
        .isNotEqualTo("6.6.6.6")
        .matches("127\\.0\\.0\\.1|0:0:0:0:0:0:0:1");
  }

  @Test
  @DisplayName(
      "AC-5: rejected and unrouted requests never reach the downstream, statuses unchanged")
  void rejectedRequestsForwardNothing() {
    int before = DOWNSTREAM.echoedRequests();
    String subject = UUID.randomUUID().toString();
    String spoof = "6.6.6.6";

    // 401: no token on a protected route
    client
        .post()
        .uri("/api/products/42")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{}")
        .header("X-Internal-Client-Ip", spoof)
        .exchange()
        .expectStatus()
        .isUnauthorized();
    // 403: valid token on a denyAll path
    client
        .get()
        .uri("/actuator/prometheus")
        .header(HttpHeaders.AUTHORIZATION, bearer(subject))
        .header("X-Internal-Client-Ip", spoof)
        .exchange()
        .expectStatus()
        .isForbidden();
    // no route: authenticated but nothing matches
    client
        .get()
        .uri("/no/such/route")
        .header(HttpHeaders.AUTHORIZATION, bearer(subject))
        .header("X-Internal-Client-Ip", spoof)
        .exchange()
        .expectStatus()
        .isNotFound();
    assertThat(DOWNSTREAM.echoedRequests()).isEqualTo(before);

    // 429: writes-per-minute=2, so the 3rd write by one subject is limited at the edge
    String limited = UUID.randomUUID().toString();
    for (int i = 0; i < 2; i++) {
      client
          .post()
          .uri("/api/users/me")
          .header(HttpHeaders.AUTHORIZATION, bearer(limited))
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("{}")
          .header("X-Internal-Client-Ip", spoof)
          .exchange()
          .expectStatus()
          .isOk();
    }
    int afterAllowed = DOWNSTREAM.echoedRequests();
    assertThat(afterAllowed).isEqualTo(before + 2);
    client
        .post()
        .uri("/api/users/me")
        .header(HttpHeaders.AUTHORIZATION, bearer(limited))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{}")
        .header("X-Internal-Client-Ip", spoof)
        .exchange()
        .expectStatus()
        .isEqualTo(429);
    assertThat(DOWNSTREAM.echoedRequests()).isEqualTo(afterAllowed);
  }

  @TestConfiguration(proxyBeanMethods = false)
  @LoadBalancerClients({
    @LoadBalancerClient(name = "AUTH-SERVICE", configuration = AuthInstances.class),
    @LoadBalancerClient(name = "PRODUCT-SERVICE", configuration = ProductInstances.class),
    @LoadBalancerClient(name = "USER-SERVICE", configuration = UserInstances.class)
  })
  static class StubDownstreamConfiguration {}

  static class AuthInstances {
    @Bean
    ServiceInstanceListSupplier instances(Environment environment) {
      return StubServiceInstances.at("AUTH-SERVICE", stubPort(environment));
    }
  }

  static class ProductInstances {
    @Bean
    ServiceInstanceListSupplier instances(Environment environment) {
      return StubServiceInstances.at("PRODUCT-SERVICE", stubPort(environment));
    }
  }

  static class UserInstances {
    @Bean
    ServiceInstanceListSupplier instances(Environment environment) {
      return StubServiceInstances.at("USER-SERVICE", stubPort(environment));
    }
  }

  private static int stubPort(Environment environment) {
    return Integer.parseInt(environment.getRequiredProperty("test.downstream.port"));
  }
}
