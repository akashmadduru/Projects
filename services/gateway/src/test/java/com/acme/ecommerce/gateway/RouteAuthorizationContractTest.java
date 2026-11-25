package com.acme.ecommerce.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.security.Roles;
import com.acme.ecommerce.gateway.support.EdgeRules;
import com.acme.ecommerce.gateway.support.EdgeRules.Kind;
import com.acme.ecommerce.gateway.support.RouteTable;
import com.acme.ecommerce.gateway.support.StubHttpServer;
import com.acme.ecommerce.gateway.support.TestKeys;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
import org.springframework.http.server.PathContainer;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.pattern.PathPatternParser;
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

  // ---------------------------------------------------------------------------------------------
  // Fixtures.
  // ---------------------------------------------------------------------------------------------

  private static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

  private static final String CUSTOMER = "CUSTOMER";

  private static final String CUSTOMER_TOKEN = token(CUSTOMER);
  private static final String ADMIN_TOKEN = token(Roles.ADMIN);
  private static final String SUPPORT_TOKEN = token(Roles.SUPPORT);
  private static final String FINANCE_TOKEN = token(Roles.FINANCE);
  private static final String CATALOG_MANAGER_TOKEN = token(Roles.CATALOG_MANAGER);
  private static final String SELLER_TOKEN = token(Roles.SELLER);

  private static String token(String role) {
    return KEYS.accessToken(
        "3f5c1c6a-8f1e-4f0a-9d3a-7d6a2f1b2c3d",
        role.toLowerCase() + "@example.com",
        "password",
        List.of(role));
  }

  /** Not rejected by the edge's rules: anything but 401/403 (downstream is unreachable here). */
  static boolean passes(int status) {
    return status != 401 && status != 403;
  }

  /**
   * A concrete request path for a pattern: {@code **} to a segment, {@code *} and {@code {x}} to 1.
   */
  static String probePath(String pattern) {
    return pattern.replace("**", "contract-probe").replaceAll("\\{[^/]+}", "1").replace("*", "1");
  }

  private EdgeRules rules() {
    return EdgeRules.read(securityChain);
  }

  /** The five documented classes of AC-2. */
  static String classOf(EdgeRules.Entry entry) {
    return switch (entry.decision().kind()) {
      case PERMIT ->
          EdgeRules.ANY.equals(entry.method()) ? "public-any-method" : "public-method-specific";
      case AUTHENTICATED -> "authenticated";
      case ROLE -> "role-restricted";
      case DENY -> "denied";
    };
  }

  private static final Set<String> CLASSES =
      Set.of(
          "public-any-method",
          "public-method-specific",
          "authenticated",
          "role-restricted",
          "denied");

  /** A reviewed permit: method (or ANY), pattern, and why it is allowed without a token. */
  record Permit(String method, String pattern, String reason) {
    String key() {
      return method + " " + pattern;
    }
  }

  private static Permit permit(String method, String pattern, String reason) {
    return new Permit(method, pattern, reason);
  }

  /**
   * Every permit the edge may have, each with a written reason. A new {@code permitAll} fails
   * {@link #permitsAreReviewed} until it is added here (AC-12); a removed one leaves a stale entry.
   */
  static final List<Permit> REVIEWED_PERMITS =
      List.of(
          permit("ANY", "/actuator/health", "liveness/readiness probe"),
          permit("ANY", "/actuator/health/**", "liveness/readiness probe groups"),
          permit("ANY", "/api/auth/login", "no token before login"),
          permit("ANY", "/api/auth/register", "no token before registration"),
          permit("ANY", "/api/auth/refresh", "refresh cookie, not a bearer token"),
          permit("ANY", "/api/auth/logout", "refresh cookie, not a bearer token"),
          permit("ANY", "/api/auth/password/forgot", "pre-authentication"),
          permit("ANY", "/api/auth/password/reset", "pre-authentication, reset token"),
          permit("ANY", "/api/auth/email/verify", "pre-authentication, emailed token"),
          permit("ANY", "/api/auth/otp/request", "OTP login is pre-authentication"),
          permit("ANY", "/api/auth/otp/verify", "OTP login is pre-authentication"),
          permit("ANY", "/api/auth/2fa/verify", "second factor, authenticated by the mfaToken"),
          permit("ANY", "/.well-known/jwks.json", "public keys; circular to protect"),
          permit("ANY", "/oauth2/**", "Google leg; state and PKCE are the controls"),
          permit("ANY", "/login/oauth2/**", "Google leg; state and PKCE are the controls"),
          permit("GET", "/api/products", "public catalog read"),
          permit("GET", "/api/products/**", "public catalog read"),
          permit("GET", "/api/categories", "public catalog read"),
          permit("GET", "/api/categories/**", "public catalog read"),
          permit("POST", "/api/products/*/views", "anonymous view tracking"),
          permit("GET", "/sitemap.xml", "SEO sitemap"),
          permit("GET", "/api/ratings/products/*", "public review list"),
          permit("GET", "/api/ratings/products/*/summary", "public rating summary"),
          permit("GET", "/api/ratings/products/*/questions", "public Q&A"),
          permit("GET", "/api/ratings/questions/*/answers", "public Q&A"),
          permit("GET", "/api/ratings/media/*", "public review media"),
          permit("GET", "/api/promotions/offers", "public offers"),
          permit("GET", "/api/promotions/deals/active", "public deals"),
          permit("GET", "/api/promotions/exchange/programs", "public exchange programs"),
          permit(
              "GET", "/api/recommendations/products/*/bought-together", "public recommendations"),
          permit("GET", "/api/orders/delivery-estimate", "pincode delivery estimate"),
          permit("GET", "/api/push/vapid-public-key", "VAPID public key is public"),
          permit("GET", "/api/lists/shared/*", "shared-list public view"),
          permit("GET", "/api/payments/config", "publishable provider config"),
          permit("POST", "/api/payments/webhook", "Stripe-Signature verified downstream"),
          permit(
              "POST", "/api/payments/webhook/razorpay", "X-Razorpay-Signature verified downstream"),
          permit("GET", "/api/sellers/*", "public seller profile"),
          permit("GET", "/api/sellers/*/feedback", "public seller feedback"),
          permit("POST", "/api/ads/*/click", "ad click beacon, no JWT"),
          permit("POST", "/api/ads/impressions", "ad impression beacon, no JWT"),
          permit("GET", "/api/support/articles", "help-centre read"),
          permit("GET", "/api/support/articles/*", "help-centre read"),
          permit("POST", "/api/support/articles/*/feedback", "article feedback"),
          permit("POST", "/api/support/chat", "chatbot reads the JWT itself when present"));

  // ---------------------------------------------------------------------------------------------
  // AC-2 and AC-12: classification table and the reviewed permit list.
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("AC-2: every route pattern x method gets one of the five documented classes")
  void classificationTable() {
    EdgeRules rules = rules();
    List<String> table = new ArrayList<>();
    for (var row : RouteTable.load().rows()) {
      String probe = probePath(row.getValue());
      for (String method : METHODS) {
        EdgeRules.Entry entry = rules.classify(method, probe);
        String cls = classOf(entry);
        assertThat(cls).as("%s %s %s", row.getKey(), method, probe).isIn(CLASSES);
        table.add(
            String.format(
                "%-14s %-6s %-48s %-22s via %s", row.getKey(), method, probe, cls, entry));
      }
    }
    assertThat(table).as("classification rows").isNotEmpty();
    System.out.println("ROUTE AUTHORIZATION CLASSIFICATION TABLE\n" + String.join("\n", table));
  }

  @Test
  @DisplayName("AC-12: the permits in the chain equal the reviewed permit list")
  void permitsAreReviewed() {
    Set<String> chain = new LinkedHashSet<>();
    rules().permits().forEach(e -> chain.add(e.method() + " " + e.pattern()));
    Set<String> reviewed = new LinkedHashSet<>();
    REVIEWED_PERMITS.forEach(p -> reviewed.add(p.key()));

    assertThat(chain).as("permits in the chain").isNotEmpty();
    Set<String> unreviewed = new TreeSet<>(chain);
    unreviewed.removeAll(reviewed);
    Set<String> stale = new TreeSet<>(reviewed);
    stale.removeAll(chain);
    assertThat(unreviewed)
        .as("unreviewed permit (add to REVIEWED_PERMITS with a reason, or remove the permitAll)")
        .isEmpty();
    assertThat(stale).as("stale reviewed permit (no longer in the chain)").isEmpty();
    assertThat(REVIEWED_PERMITS).allSatisfy(p -> assertThat(p.reason()).isNotBlank());
  }

  // ---------------------------------------------------------------------------------------------
  // AC-6, AC-7: every permit is backed by a route.
  // ---------------------------------------------------------------------------------------------

  /** Permits that are served by the gateway itself, so no route matches them. Reason required. */
  private static final Map<String, String> UNROUTED_ALLOWED =
      Map.of(
          "/actuator/health", "served by the gateway's own actuator, not a route",
          "/actuator/health/**", "served by the gateway's own actuator, not a route");

  private static final PathPatternParser PARSER = new PathPatternParser();

  private static boolean routed(RouteTable table, String probe) {
    PathContainer path = PathContainer.parsePath(probe);
    return table.rows().stream().anyMatch(row -> PARSER.parse(row.getValue()).matches(path));
  }

  @Test
  @DisplayName("AC-6, AC-7: every permitted pattern matches at least one route (allow-list aside)")
  void everyPermitHasARoute() {
    RouteTable table = RouteTable.load();
    EdgeRules rules = rules();
    assertThat(rules.permits()).as("permits").isNotEmpty();

    for (EdgeRules.Entry permit : rules.permits()) {
      String probe = probePath(permit.pattern());
      boolean routed = routed(table, probe);
      if (UNROUTED_ALLOWED.containsKey(permit.pattern())) {
        assertThat(routed)
            .as("stale UNROUTED_ALLOWED entry %s: a route now matches it", permit.pattern())
            .isFalse();
      } else {
        String what =
            EdgeRules.ANY.equals(permit.method())
                ? "PUBLIC_PATHS entry " + permit.pattern()
                : "method-specific permit " + permit.method() + " " + permit.pattern();
        assertThat(routed).as(what + " matches no route (probe " + probe + ")").isTrue();
      }
    }
    UNROUTED_ALLOWED.forEach(
        (pattern, reason) -> {
          assertThat(reason).isNotBlank();
          assertThat(rules.permits().stream().map(EdgeRules.Entry::pattern))
              .as("UNROUTED_ALLOWED %s must be a real permit", pattern)
              .contains(pattern);
        });
    // The Google leg is routed to auth-service, not just permitted.
    assertThat(table.patternsById().get("auth-oauth2")).contains("/oauth2/**", "/login/oauth2/**");
  }
}
