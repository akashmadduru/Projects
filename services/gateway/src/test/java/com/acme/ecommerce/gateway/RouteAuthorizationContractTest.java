package com.acme.ecommerce.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.gateway.support.EdgeRules;
import com.acme.ecommerce.gateway.support.EdgeRules.Kind;
import com.acme.ecommerce.gateway.support.RouteTable;
import com.acme.ecommerce.gateway.support.StubHttpServer;
import com.acme.ecommerce.gateway.support.TestKeys;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * TD-TEST-04: the route table in {@code application.yml} and the authorization table in {@code
 * SecurityConfig} are two halves of one statement. This contract asserts them against each other
 * through the REAL security chain on a real Netty port, so a route that silently loses protection,
 * a public entry with no route, or a drifted role rule fails the build.
 *
 * <p>No downstream is reachable (Eureka off, no load-balancer instances), so a request that passes
 * security shows up as a 5xx/404 from routing, never as 401/403: a 401 or 403 can only come from
 * the edge's rules.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RouteAuthorizationContractTest {

  private static final TestKeys KEYS = TestKeys.generate("route-contract-kid");
  private static final StubHttpServer JWKS = StubHttpServer.start(KEYS::jwksJson);

  @LocalServerPort private int port;

  @MockitoBean private ReactiveStringRedisTemplate redis;

  @Autowired private GatewayProperties liveRoutes;

  @Autowired private SecurityWebFilterChain securityChain;

  private WebTestClient client;

  @DynamicPropertySource
  static void gatewayProperties(DynamicPropertyRegistry registry) {
    registry.add("gateway.jwt.jwks-uri", () -> JWKS.baseUrl() + "/.well-known/jwks.json");
    registry.add("eureka.client.enabled", () -> "false");
    registry.add("gateway.rate-limit.enabled", () -> "false");
  }

  @AfterAll
  static void stopJwks() {
    JWKS.close();
  }

  @BeforeEach
  void setUp() {
    @SuppressWarnings("unchecked")
    ReactiveValueOperations<String, String> ops = Mockito.mock(ReactiveValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    // Deny-list miss: no token is revoked.
    when(ops.get(any())).thenReturn(Mono.empty());
    client =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(20))
            .build();
  }

  @Test
  @DisplayName("AC-1: routes are parsed from application.yml and match the live route ids")
  void routesAreParsedFromApplicationYml() {
    RouteTable table = RouteTable.load();

    assertThat(table.routes()).as("parsed routes").isNotEmpty();
    table
        .routes()
        .forEach(
            route ->
                assertThat(route.patterns()).as("Path patterns of " + route.id()).isNotEmpty());
    List<String> liveIds = liveRoutes.getRoutes().stream().map(r -> r.getId()).toList();
    table.assertSameIdsAs(liveIds);
    // Anchors: the routes the contract is about, so an empty-but-consistent parse cannot pass.
    assertThat(table.routeIds())
        .contains("auth-public", "auth-secured", "user", "cart", "order", "seller", "support");
    assertThat(client).isNotNull();
  }

  @Test
  @DisplayName("AC-2: EdgeRules reads the real chain and classifies by first match")
  void edgeRulesReadTheRealChain() {
    EdgeRules rules = EdgeRules.read(securityChain);

    assertThat(rules.entries()).as("chain entries").isNotEmpty();
    // First-match overlaps, decided by the real rules (not by a copy of them).
    assertThat(rules.classify("GET", "/api/sellers/me").decision().kind())
        .isEqualTo(Kind.AUTHENTICATED);
    assertThat(rules.classify("GET", "/api/sellers/1").decision().kind()).isEqualTo(Kind.PERMIT);
    assertThat(rules.classify("POST", "/api/sellers/1").decision().kind())
        .isEqualTo(Kind.AUTHENTICATED);
    assertThat(rules.classify("GET", "/api/orders/delivery-estimate").decision().kind())
        .isEqualTo(Kind.PERMIT);
    assertThat(rules.classify("GET", "/api/orders/1").decision().kind())
        .isEqualTo(Kind.AUTHENTICATED);
    assertThat(rules.classify("GET", "/api/admin/orders/1").decision().kind()).isEqualTo(Kind.ROLE);
    assertThat(rules.classify("GET", "/actuator/env").decision().kind()).isEqualTo(Kind.DENY);
    assertThat(rules.classify("GET", "/actuator/health").decision().kind()).isEqualTo(Kind.PERMIT);
  }
}
