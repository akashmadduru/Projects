package com.acme.ecommerce.order.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import feign.Client;
import feign.Request;
import feign.Response;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DependencyBreakerCapabilityTest {

  private final CircuitBreakerRegistry registry = DependencyBreakerCapability.defaultRegistry();
  private final DependencyBreakerCapability capability = new DependencyBreakerCapability(registry);
  private final AtomicInteger calls = new AtomicInteger();

  private static Request request(String url) {
    return Request.create(
        Request.HttpMethod.GET, url, Map.of(), null, StandardCharsets.UTF_8, null);
  }

  private Client answering(int status) {
    return (req, opts) -> {
      calls.incrementAndGet();
      return Response.builder().status(status).request(req).headers(Map.of()).build();
    };
  }

  @Test
  @DisplayName("ten 5xx answers open the breaker, then calls fail fast without reaching the host")
  void opensOnServerErrors() throws IOException {
    Client client = capability.enrich(answering(503));
    for (int i = 0; i < 10; i++) {
      client.execute(request("http://cart-service/api/cart"), new Request.Options());
    }
    assertThat(registry.circuitBreaker("dep-cart-service").getState())
        .isEqualTo(CircuitBreaker.State.OPEN);

    assertThatThrownBy(
            () -> client.execute(request("http://cart-service/api/cart"), new Request.Options()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Circuit open for cart-service");
    assertThat(calls).hasValue(10);
  }

  @Test
  @DisplayName("4xx answers are the dependency working: they never open the breaker")
  void clientErrorsDoNotCount() throws IOException {
    Client client = capability.enrich(answering(409));
    for (int i = 0; i < 25; i++) {
      client.execute(request("http://inventory-service/x"), new Request.Options());
    }
    assertThat(registry.circuitBreaker("dep-inventory-service").getState())
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("each service has its own breaker; I/O errors count as failures")
  void perServiceAndIoErrors() throws IOException {
    Client failing =
        capability.enrich(
            (Client)
                (req, opts) -> {
                  throw new IOException("connection refused");
                });
    for (int i = 0; i < 10; i++) {
      assertThatThrownBy(
              () -> failing.execute(request("http://payment-service/p"), new Request.Options()))
          .isInstanceOf(IOException.class);
    }
    assertThat(registry.circuitBreaker("dep-payment-service").getState())
        .isEqualTo(CircuitBreaker.State.OPEN);

    Client healthy = capability.enrich(answering(200));
    assertThat(healthy.execute(request("http://user-service/u"), new Request.Options()).status())
        .isEqualTo(200);
  }
}
