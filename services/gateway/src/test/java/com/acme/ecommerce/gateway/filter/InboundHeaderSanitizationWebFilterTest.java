package com.acme.ecommerce.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The header-spoofing test.
 *
 * <p>This is the control that makes every downstream service's trust in {@code X-User-Id}
 * justified. If it regresses, nothing fails loudly - requests keep working, they just start
 * carrying whatever identity the client asked for.
 */
class InboundHeaderSanitizationWebFilterTest {

  private final InboundHeaderSanitizationWebFilter filter =
      new InboundHeaderSanitizationWebFilter();

  @Test
  @DisplayName("strips every client-supplied header in the trusted namespace")
  void stripsSpoofedIdentityHeaders() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/products/1")
                .header(GatewayHeaders.USER_ID, "00000000-0000-0000-0000-00000000dead")
                .header(GatewayHeaders.USER_ROLES, "ADMIN")
                .header(GatewayHeaders.USER_EMAIL, "attacker@example.com")
                .header(GatewayHeaders.AUTH_METHOD, "password")
                .header("X-Internal-Trace", "yes")
                // Case is irrelevant to HTTP and must be irrelevant here.
                .header("x-user-tenant", "acme")
                .build());

    HttpHeaders seen = capture(exchange);

    assertThat(seen.headerNames())
        .noneSatisfy(name -> assertThat(GatewayHeaders.isUntrustedInbound(name)).isTrue());
  }

  @Test
  @DisplayName("leaves Authorization and other legitimate headers alone")
  void keepsLegitimateHeaders() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/products/1")
                // `x-auth-` must not match `authorization`: the bearer token is the ONE
                // client-supplied credential the gateway wants.
                .header(HttpHeaders.AUTHORIZATION, "Bearer some-token")
                .header(GatewayHeaders.CORRELATION_ID, "b0a1e1f2-0000-4000-8000-000000000001")
                .header(HttpHeaders.ACCEPT, "application/json")
                .header(GatewayHeaders.USER_ID, "spoofed")
                .build());

    HttpHeaders seen = capture(exchange);

    assertThat(seen.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer some-token");
    assertThat(seen.getFirst(GatewayHeaders.CORRELATION_ID))
        .isEqualTo("b0a1e1f2-0000-4000-8000-000000000001");
    assertThat(seen.getFirst(HttpHeaders.ACCEPT)).isEqualTo("application/json");
    assertThat(seen.getFirst(GatewayHeaders.USER_ID)).isNull();
  }

  @Test
  @DisplayName("passes a clean request through untouched")
  void passesCleanRequestThrough() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/products").build());

    AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
    StepVerifier.create(
            filter.filter(
                exchange,
                downstream -> {
                  captured.set(downstream);
                  return Mono.empty();
                }))
        .verifyComplete();

    // Same exchange instance, not a copy: no allocation on the overwhelmingly common path.
    assertThat(captured.get()).isSameAs(exchange);
  }

  @Test
  @DisplayName("runs before Spring Security and before the correlation filter")
  void ordersAheadOfEverythingElse() {
    // Not a tautology: the ordering IS the security property. Running after Spring Security's
    // chain (-100) would leave a window in which an authentication component could observe the
    // client's forged headers.
    assertThat(filter.getOrder()).isEqualTo(-300);
    assertThat(filter.getOrder()).isLessThan(CorrelationIdWebFilter.ORDER);
    assertThat(filter.getOrder()).isLessThan(-100);
  }

  private HttpHeaders capture(MockServerWebExchange exchange) {
    AtomicReference<HttpHeaders> seen = new AtomicReference<>();
    StepVerifier.create(
            filter.filter(
                exchange,
                downstream -> {
                  seen.set(downstream.getRequest().getHeaders());
                  return Mono.empty();
                }))
        .verifyComplete();
    return seen.get();
  }
}
