package com.acme.ecommerce.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The other half of the spoofing story: what the gateway PUTS on the proxied request.
 *
 * <p>{@link InboundHeaderSanitizationWebFilterTest} proves the client's version is destroyed; this
 * proves the replacement comes from the token and from nowhere else.
 */
class IdentityPropagationGatewayFilterTest {

  private static final String SUBJECT = "3f5c1c6a-8f1e-4f0a-9d3a-7d6a2f1b2c3d";

  private final IdentityPropagationGatewayFilter filter = new IdentityPropagationGatewayFilter();

  @Test
  @DisplayName("injects identity headers derived only from validated token claims")
  void injectsTokenDerivedIdentity() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/products").build());

    HttpHeaders proxied = run(exchange, authentication(SUBJECT, "buyer@example.com", "google"));

    assertThat(proxied.getFirst(GatewayHeaders.USER_ID)).isEqualTo(SUBJECT);
    assertThat(proxied.getFirst(GatewayHeaders.USER_EMAIL)).isEqualTo("buyer@example.com");
    assertThat(proxied.getFirst(GatewayHeaders.AUTH_METHOD)).isEqualTo("google");
    // UNPREFIXED, exactly as the `roles` claim spells them - so the header and the token a
    // downstream independently validates cannot disagree.
    assertThat(proxied.getFirst(GatewayHeaders.USER_ROLES)).isEqualTo("VIEWER,ADMIN");
  }

  @Test
  @DisplayName("a client-supplied identity header is overwritten, never merged")
  void clientSuppliedHeaderIsOverwritten() {
    // Belt and braces: the sanitisation filter has already removed this in production. The
    // assertion here is that even if it somehow survived, the token still wins - `set`, not `add`.
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/products")
                .header(GatewayHeaders.USER_ID, "00000000-0000-0000-0000-00000000dead")
                .header(GatewayHeaders.USER_ROLES, "ADMIN")
                .build());

    HttpHeaders proxied = run(exchange, authentication(SUBJECT, "viewer@example.com", "password"));

    assertThat(proxied.get(GatewayHeaders.USER_ID)).containsExactly(SUBJECT);
    assertThat(proxied.get(GatewayHeaders.USER_ROLES)).containsExactly("VIEWER,ADMIN");
  }

  @Test
  @DisplayName("adds no identity headers at all on an anonymous route")
  void anonymousRouteGetsNoIdentity() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login").build());

    AtomicReference<HttpHeaders> proxied = new AtomicReference<>();
    StepVerifier.create(filter.filter(exchange, capture(proxied))).verifyComplete();

    // Not blank values - ABSENT. A downstream receiving `X-User-Id: ""` has to decide what an
    // empty user id means, and one of the plausible answers is "matches everything".
    assertThat(proxied.get().getFirst(GatewayHeaders.USER_ID)).isNull();
    assertThat(proxied.get().getFirst(GatewayHeaders.USER_ROLES)).isNull();
    assertThat(proxied.get().getFirst(GatewayHeaders.USER_EMAIL)).isNull();
    assertThat(proxied.get().getFirst(GatewayHeaders.AUTH_METHOD)).isNull();
    // Correlation is not identity, and an unauthenticated request still needs to be traceable.
    assertThat(proxied.get().getFirst(GatewayHeaders.CORRELATION_ID)).isNotBlank();
  }

  @Test
  @DisplayName("drops a claim carrying control characters rather than forwarding it")
  void dropsUnsafeClaimValues() {
    Jwt jwt =
        jwt(SUBJECT, "victim@example.com\r\nX-User-Roles: ADMIN", "password", List.of("VIEWER"));
    Authentication authentication =
        new JwtAuthenticationToken(
            jwt, List.of(new SimpleGrantedAuthority("ROLE_VIEWER")), SUBJECT);

    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/products").build());

    HttpHeaders proxied = run(exchange, authentication);

    // The email is machine-readable identity, not display text; a value that would need escaping
    // is a value that must not become a header at all.
    assertThat(proxied.getFirst(GatewayHeaders.USER_EMAIL)).isNull();
    assertThat(proxied.getFirst(GatewayHeaders.USER_ID)).isEqualTo(SUBJECT);
    assertThat(proxied.get(GatewayHeaders.USER_ROLES)).containsExactly("VIEWER");
  }

  @Test
  @DisplayName("ignores a non-JWT authentication rather than trusting its principal name")
  void ignoresNonJwtAuthentication() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/products").build());

    HttpHeaders proxied =
        run(exchange, new TestingAuthenticationToken("someone", "creds", "ROLE_ADMIN"));

    assertThat(proxied.getFirst(GatewayHeaders.USER_ID)).isNull();
  }

  @Test
  @DisplayName("runs post-authentication and pre-routing")
  void ordersBetweenSecurityAndRouting() {
    // 0 is after Spring Security's WebFilter chain (-100) and well before RouteToRequestUrlFilter
    // (10000) and ReactiveLoadBalancerClientFilter (10150). A GlobalFilter ordered after those
    // would mutate a request that has already been dispatched.
    assertThat(filter.getOrder()).isZero();
  }

  private HttpHeaders run(MockServerWebExchange exchange, Authentication authentication) {
    AtomicReference<HttpHeaders> proxied = new AtomicReference<>();
    StepVerifier.create(
            filter
                .filter(exchange, capture(proxied))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)))
        .verifyComplete();
    return proxied.get();
  }

  private static GatewayFilterChain capture(AtomicReference<HttpHeaders> target) {
    return downstream -> {
      target.set(downstream.getRequest().getHeaders());
      return Mono.empty();
    };
  }

  private static Authentication authentication(String subject, String email, String authMethod) {
    Jwt jwt = jwt(subject, email, authMethod, List.of("VIEWER", "ADMIN"));
    return new JwtAuthenticationToken(
        jwt,
        List.of(
            new SimpleGrantedAuthority("ROLE_VIEWER"), new SimpleGrantedAuthority("ROLE_ADMIN")),
        subject);
  }

  private static Jwt jwt(String subject, String email, String authMethod, List<String> roles) {
    return new Jwt(
        "header.payload.signature",
        Instant.now(),
        Instant.now().plusSeconds(600),
        Map.of("alg", "RS256", "kid", "test"),
        Map.of(
            "sub", subject,
            "email", email,
            "auth_method", authMethod,
            "roles", roles));
  }
}
