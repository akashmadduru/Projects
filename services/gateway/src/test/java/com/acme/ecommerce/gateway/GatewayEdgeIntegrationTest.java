package com.acme.ecommerce.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.gateway.filter.GatewayHeaders;
import com.acme.ecommerce.gateway.support.StubHttpServer;
import com.acme.ecommerce.gateway.support.StubServiceInstances;
import com.acme.ecommerce.gateway.support.TestKeys;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * End-to-end behaviour of the edge, with a real Netty server, the real security chain, the real
 * gateway filter pipeline and a real (stub) downstream on a real socket.
 *
 * <p>Deliberately full-stack. Every property this class asserts - which routes are open, that a
 * spoofed header cannot get through, that CORS emits one header and not two - is an emergent
 * property of several components' ORDER relative to each other. A unit test of any one of them
 * would keep passing while the composition broke.
 *
 * <p>No Eureka and no Testcontainers: {@link StubServiceInstances} replaces the registry, and the
 * downstream is a JDK HTTP server. That keeps the suite fast and hermetic while still exercising
 * {@code lb://} resolution and the Netty proxy path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayEdgeIntegrationTest.StubDownstreamConfiguration.class)
class GatewayEdgeIntegrationTest {

  private static final String SUBJECT = "3f5c1c6a-8f1e-4f0a-9d3a-7d6a2f1b2c3d";
  private static final String ALLOWED_ORIGIN = "http://localhost:5173";

  private static final TestKeys KEYS = TestKeys.generate("gateway-test-kid");
  private static final StubHttpServer DOWNSTREAM = StubHttpServer.start(KEYS::jwksJson);

  @LocalServerPort private int port;

  private WebTestClient client;

  @DynamicPropertySource
  static void gatewayProperties(DynamicPropertyRegistry registry) {
    registry.add("test.downstream.port", DOWNSTREAM::port);
    // Static JWKS URL rather than discovery: the decoder's discovery path has its own unit test,
    // and pinning it here keeps this test about routing and authorization.
    registry.add("gateway.jwt.jwks-uri", () -> DOWNSTREAM.baseUrl() + "/.well-known/jwks.json");
    registry.add("gateway.cors.allowed-origins", () -> ALLOWED_ORIGIN);
    registry.add("eureka.client.enabled", () -> "false");
    // Keep the breaker out of the way of the routing assertions; the fallback test drives it
    // explicitly.
    registry.add("resilience4j.circuitbreaker.configs.default.minimum-number-of-calls", () -> 100);
  }

  @AfterAll
  static void stopDownstream() {
    DOWNSTREAM.close();
  }

  @BeforeEach
  void setUp() {
    DOWNSTREAM.setAvailable(true);
    client =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(java.time.Duration.ofSeconds(20))
            .build();
  }

  // ---------------------------------------------------------------------------
  // The route/authorization contract. This is the piece most likely to drift.
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("route authorization contract")
  class RouteAuthorizationContract {

    @Test
    @DisplayName("auth-public routes are reachable with no credentials")
    void authPublicRoutesArePublic() {
      for (String path :
          List.of(
              "/api/auth/login", "/api/auth/register", "/api/auth/refresh", "/api/auth/logout")) {
        client
            .post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus()
            .isOk();
      }
    }

    @Test
    @DisplayName("auth-secured routes require a Bearer token")
    void authSecuredRoutesRequireAToken() {
      for (String path : List.of("/api/auth/me", "/api/auth/validate")) {
        expectUnauthenticatedProblem(client.get().uri(path).exchange());
      }
      client
          .get()
          .uri("/api/auth/me")
          .header(HttpHeaders.AUTHORIZATION, bearer())
          .exchange()
          .expectStatus()
          .isOk();
    }

    @Test
    @DisplayName("the JWKS document is reachable without a token")
    void jwksIsPublic() {
      // Necessarily so: requiring a token to fetch the keys that validate tokens is a circular
      // dependency no client can break out of.
      client
          .get()
          .uri("/.well-known/jwks.json")
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .value(body -> assertThat(body).contains("\"kty\":\"RSA\"").doesNotContain("\"d\":"));
    }

    @Test
    @DisplayName("the Google OIDC leg is public")
    void oauth2LegIsPublic() {
      client.get().uri("/oauth2/authorization/google").exchange().expectStatus().isOk();
      client.get().uri("/login/oauth2/code/google?code=x&state=y").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("GET /api/products is public and reachable without a token")
    void publicProductGetRequiresNoToken() {
      // Phase 1F: product catalog reads are public. PRODUCT-SERVICE has no instances in this
      // fixture, so passing the security layer means reaching the clean 503 - NOT a 401.
      client.get().uri("/api/products").exchange().expectStatus().isEqualTo(503);
    }

    @Test
    @DisplayName("POST /api/products requires a Bearer token")
    void productPostRequiresAToken() {
      // Create/update/delete remain protected: a POST without credentials is rejected
      // at the edge with 401.
      expectUnauthenticatedProblem(
          client
              .post()
              .uri("/api/products")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue("{}")
              .exchange());
    }

    @Test
    @DisplayName("actuator health is open; everything else under /actuator is closed")
    void actuatorExposure() {
      client.get().uri("/actuator/health").exchange().expectStatus().isOk();

      // Anonymous -> 401 (get credentials); with a perfectly good token -> 403 (denyAll, and no
      // role can ever satisfy it). Neither is 200, which is the whole point: /actuator/env at the
      // public edge would publish the datasource URL and every secret-shaped property name.
      client.get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
      client
          .get()
          .uri("/actuator/prometheus")
          .header(HttpHeaders.AUTHORIZATION, bearer())
          .exchange()
          .expectStatus()
          .isForbidden();
    }

    @Test
    @DisplayName("an unmapped path is denied rather than silently allowed")
    void unmappedPathsAreDeniedByDefault() {
      // `anyExchange().authenticated()` is what makes a route added tomorrow closed until someone
      // opens it deliberately.
      expectUnauthenticatedProblem(client.get().uri("/api/orders/1").exchange());
    }
  }

  // ---------------------------------------------------------------------------
  // Token validation at the edge.
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("token validation")
  class TokenValidation {

    @Test
    @DisplayName("rejects expired, wrong-issuer and wrong-audience tokens")
    void rejectsInvalidTokens() {
      for (String token :
          List.of(
              KEYS.expiredToken(SUBJECT),
              KEYS.wrongIssuerToken(SUBJECT),
              KEYS.wrongAudienceToken(SUBJECT))) {
        expectUnauthenticatedProblem(
            client
                .get()
                .uri("/api/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange());
      }
    }

    @Test
    @DisplayName("rejects a structurally broken token without leaking why")
    void rejectsGarbage() {
      expectUnauthenticatedProblem(
          client
              .get()
              .uri("/api/auth/me")
              .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.token")
              .exchange());
    }
  }

  // ---------------------------------------------------------------------------
  // Identity propagation - the header-spoofing contract, end to end.
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("identity propagation")
  class IdentityPropagation {

    @Test
    @DisplayName("a spoofed identity header never reaches the downstream service")
    void spoofedHeadersAreReplacedWithTokenDerivedValues() {
      EntityExchangeResult<String> result =
          client
              .get()
              .uri("/api/auth/me")
              .header(HttpHeaders.AUTHORIZATION, bearer())
              // Everything a hostile client would try.
              .header(GatewayHeaders.USER_ID, "00000000-0000-0000-0000-00000000dead")
              .header(GatewayHeaders.USER_ROLES, "ADMIN")
              .header(GatewayHeaders.USER_EMAIL, "attacker@example.com")
              .header(GatewayHeaders.AUTH_METHOD, "password")
              .header("X-Internal-Bypass", "true")
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(String.class)
              .returnResult();

      String echoed = result.getResponseBody();
      assertThat(echoed).isNotNull();

      // Token-derived, not client-supplied. If this ever inverts, every downstream authorization
      // decision in the platform becomes attacker-controlled and nothing else fails.
      assertThat(echoed).contains("\"x-user-id\":\"" + SUBJECT + "\"");
      assertThat(echoed).contains("\"x-user-email\":\"user@example.com\"");
      assertThat(echoed).contains("\"x-auth-method\":\"google\"");
      assertThat(echoed).contains("\"x-user-roles\":\"VIEWER\"");
      assertThat(echoed).doesNotContain("attacker@example.com");
      assertThat(echoed).doesNotContain("00000000-0000-0000-0000-00000000dead");
      assertThat(echoed).doesNotContain("x-internal-bypass");
    }

    @Test
    @DisplayName("a public route forwards no identity headers at all")
    void publicRouteCarriesNoIdentity() {
      EntityExchangeResult<String> result =
          client
              .post()
              .uri("/api/auth/login")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue("{}")
              .header(GatewayHeaders.USER_ID, "00000000-0000-0000-0000-00000000dead")
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(String.class)
              .returnResult();

      assertThat(result.getResponseBody()).doesNotContain("x-user-id");
      // Correlation is not identity; an unauthenticated request must still be traceable.
      assertThat(result.getResponseBody()).contains("x-correlation-id");
    }

    @Test
    @DisplayName("a well-formed inbound correlation id is honoured end to end")
    void correlationIdIsPropagatedAndEchoed() {
      String correlationId = UUID.randomUUID().toString();

      EntityExchangeResult<String> result =
          client
              .post()
              .uri("/api/auth/login")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue("{}")
              .header(GatewayHeaders.CORRELATION_ID, correlationId)
              .exchange()
              .expectStatus()
              .isOk()
              .expectHeader()
              .valueEquals(GatewayHeaders.CORRELATION_ID, correlationId)
              .expectBody(String.class)
              .returnResult();

      assertThat(result.getResponseBody()).contains(correlationId);
    }

    @Test
    @DisplayName("a malformed inbound correlation id is replaced, not forwarded")
    void malformedCorrelationIdIsReplaced() {
      EntityExchangeResult<String> result =
          client
              .post()
              .uri("/api/auth/login")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue("{}")
              .header(GatewayHeaders.CORRELATION_ID, "please-log-me-verbatim")
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(String.class)
              .returnResult();

      assertThat(result.getResponseBody()).doesNotContain("please-log-me-verbatim");
    }
  }

  // ---------------------------------------------------------------------------
  // CORS - configured in exactly one place.
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("CORS")
  class Cors {

    @Test
    @DisplayName("a preflight emits EXACTLY ONE Access-Control-Allow-Origin header")
    void preflightEmitsASingleAllowOriginHeader() {
      EntityExchangeResult<byte[]> result =
          client
              .method(HttpMethod.OPTIONS)
              .uri("/api/auth/login")
              .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
              .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
              .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type")
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody()
              .returnResult();

      List<String> allowOrigin =
          result.getResponseHeaders().get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);

      // THE trap this asserts against: configuring CORS both in the Security chain and in
      // spring.cloud.gateway...globalcors makes the preflight pass through two handlers and emit
      // the header twice. Browsers reject that outright, and the console message points at
      // neither configuration.
      assertThat(allowOrigin).containsExactly(ALLOWED_ORIGIN);
      assertThat(result.getResponseHeaders().get(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
          .containsExactly("true");
      assertThat(result.getResponseHeaders().getAccessControlExposeHeaders())
          .contains(GatewayHeaders.CORRELATION_ID);
    }

    @Test
    @DisplayName("a proxied cross-origin response is deduped even though the downstream sets CORS")
    void proxiedResponseIsDedupedAgainstADownstreamThatAlsoSetsCors() {
      // The stub answers like auth-service really does: it has its own CorsConfigurationSource and
      // returns its own Access-Control-* headers. Without DedupeResponseHeader the client sees TWO
      // Access-Control-Allow-Origin values and the browser rejects the response outright.
      //
      // This case was found by curling the live stack, not by this suite - the stub used to be
      // politer than any real service. Encoded here so it cannot come back.
      EntityExchangeResult<byte[]> result =
          client
              .post()
              .uri("/api/auth/login")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue("{}")
              .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody()
              .returnResult();

      assertThat(result.getResponseHeaders().get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
          .containsExactly(ALLOWED_ORIGIN);
      assertThat(result.getResponseHeaders().get(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
          .containsExactly("true");
      // Vary is legitimately multi-valued; the assertion is that it holds no duplicates.
      List<String> vary = result.getResponseHeaders().get(HttpHeaders.VARY);
      assertThat(vary).isNotNull();
      assertThat(vary).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("an unlisted origin is refused")
    void unknownOriginIsRefused() {
      client
          .method(HttpMethod.OPTIONS)
          .uri("/api/auth/login")
          .header(HttpHeaders.ORIGIN, "https://evil.example.com")
          .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
          .exchange()
          .expectStatus()
          .isForbidden();
    }
  }

  // ---------------------------------------------------------------------------
  // Failure handling.
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("failure handling")
  class FailureHandling {

    @Test
    @DisplayName("an unresolvable service produces a clean 503 problem document, not a stack trace")
    void unresolvableServiceFallsBackCleanly() {
      // PRODUCT-SERVICE has no registered instances - exactly the Phase 1E situation.
      client
          .get()
          .uri("/api/products/42")
          .header(HttpHeaders.AUTHORIZATION, bearer())
          .exchange()
          .expectStatus()
          .isEqualTo(503)
          .expectHeader()
          .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
          .expectBody()
          .jsonPath("$.code")
          .isEqualTo("UPSTREAM_UNAVAILABLE")
          .jsonPath("$.service")
          .isEqualTo("product")
          .jsonPath("$.correlationId")
          .exists();
    }

    @Test
    @DisplayName("a downstream error body is never forwarded verbatim by the fallback")
    void downstreamBodiesAreNotLeaked() {
      // The gateway's own failure responses must not echo whatever the downstream said; that is
      // how internal hostnames, ports and framework versions escape.
      client
          .get()
          .uri("/api/products/42")
          .header(HttpHeaders.AUTHORIZATION, bearer())
          .exchange()
          .expectBody(String.class)
          .value(body -> assertThat(body).doesNotContain("PRODUCT-SERVICE"));
    }

    @Test
    @DisplayName("every gateway-generated failure carries a correlation id")
    void failuresAreCorrelated() {
      String correlationId = UUID.randomUUID().toString();
      // POST, because GET /api/products/** is public and would not fail at the edge.
      client
          .post()
          .uri("/api/products/42")
          .header(GatewayHeaders.CORRELATION_ID, correlationId)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("{}")
          .exchange()
          .expectStatus()
          .isUnauthorized()
          .expectHeader()
          .valueEquals(GatewayHeaders.CORRELATION_ID, correlationId)
          .expectBody()
          .jsonPath("$.correlationId")
          .isEqualTo(correlationId);
    }
  }

  private static String bearer() {
    return "Bearer " + KEYS.accessToken(SUBJECT, "user@example.com", "google", List.of("VIEWER"));
  }

  private static void expectUnauthenticatedProblem(WebTestClient.ResponseSpec response) {
    response
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("UNAUTHENTICATED")
        .jsonPath("$.status")
        .isEqualTo(401)
        .jsonPath("$.type")
        .exists()
        .jsonPath("$.correlationId")
        .exists();
  }

  /**
   * Replaces Eureka with fixed instance lists.
   *
   * <p>AUTH-SERVICE points at the stub; PRODUCT-SERVICE points at nothing, which is both the Phase
   * 1E reality and the input the circuit-breaker fallback test needs.
   */
  @TestConfiguration(proxyBeanMethods = false)
  @LoadBalancerClients({
    @LoadBalancerClient(name = "AUTH-SERVICE", configuration = AuthServiceInstances.class),
    @LoadBalancerClient(name = "PRODUCT-SERVICE", configuration = ProductServiceInstances.class)
  })
  static class StubDownstreamConfiguration {}

  /** Per-service child-context configuration for AUTH-SERVICE. */
  static class AuthServiceInstances {
    @Bean
    ServiceInstanceListSupplier authServiceInstanceListSupplier(Environment environment) {
      int port = Integer.parseInt(environment.getRequiredProperty("test.downstream.port"));
      return StubServiceInstances.at("AUTH-SERVICE", port);
    }
  }

  /** Per-service child-context configuration for PRODUCT-SERVICE: nothing registered. */
  static class ProductServiceInstances {
    @Bean
    ServiceInstanceListSupplier productServiceInstanceListSupplier() {
      return StubServiceInstances.none("PRODUCT-SERVICE");
    }
  }
}
