package com.acme.ecommerce.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CorrelationIdWebFilterTest {

  private final CorrelationIdWebFilter filter = new CorrelationIdWebFilter();

  @Test
  @DisplayName("accepts a well-formed inbound UUID")
  void acceptsInboundUuid() {
    String inbound = UUID.randomUUID().toString();
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/products")
                .header(GatewayHeaders.CORRELATION_ID, inbound)
                .build());

    run(exchange);

    assertThat(CorrelationIdWebFilter.correlationId(exchange)).isEqualTo(inbound);
  }

  @Test
  @DisplayName("rejects a malformed inbound value and generates a fresh id")
  void rejectsMalformedInboundValue() {
    // A client-chosen string here would be echoed into a response header and into every log line
    // for the request - i.e. a header-injection and log-injection primitive in one.
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/products")
                .header(GatewayHeaders.CORRELATION_ID, "not-a-uuid\r\nX-Injected: yes")
                .build());

    run(exchange);

    String resolved = CorrelationIdWebFilter.correlationId(exchange);
    assertThat(resolved).doesNotContain("X-Injected");
    assertThat(UUID.fromString(resolved)).hasToString(resolved);
  }

  @Test
  @DisplayName("generates an id when none is supplied")
  void generatesWhenAbsent() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/products").build());

    run(exchange);

    assertThat(CorrelationIdWebFilter.correlationId(exchange)).isNotBlank();
  }

  @Test
  @DisplayName("publishes the id to the Reactor context for downstream operators")
  void publishesToReactorContext() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/products").build());

    AtomicReference<String> fromContext = new AtomicReference<>();
    StepVerifier.create(
            filter.filter(
                exchange,
                downstream ->
                    Mono.deferContextual(
                            context -> {
                              fromContext.set(context.get(CorrelationIdWebFilter.CONTEXT_KEY));
                              return Mono.empty();
                            })
                        .then()))
        .verifyComplete();

    assertThat(fromContext.get()).isEqualTo(CorrelationIdWebFilter.correlationId(exchange));
  }

  @Test
  @DisplayName("runs after sanitisation but before Spring Security, so 401s are correlated too")
  void ordersBetweenSanitisationAndSecurity() {
    assertThat(filter.getOrder()).isEqualTo(-250);
    assertThat(filter.getOrder()).isGreaterThan(InboundHeaderSanitizationWebFilter.ORDER);
    assertThat(filter.getOrder()).isLessThan(-100);
  }

  private void run(MockServerWebExchange exchange) {
    StepVerifier.create(filter.filter(exchange, downstream -> Mono.empty())).verifyComplete();
  }
}
