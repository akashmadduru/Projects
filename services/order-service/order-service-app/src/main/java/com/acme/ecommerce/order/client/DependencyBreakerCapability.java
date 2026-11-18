package com.acme.ecommerce.order.client;

import feign.Capability;
import feign.Client;
import feign.Request;
import feign.Response;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * TD-BE-07: one circuit breaker per downstream service (the request host: the discovery name such
 * as {@code cart-service}), wrapped around Feign's HTTP client.
 *
 * <p>Wrapping the CLIENT, not the Feign method, keeps every existing error path unchanged: an open
 * breaker throws an {@link IOException}, which Feign turns into its usual {@code
 * RetryableException} (a {@code FeignException}), so the 49 call sites that already map Feign
 * failures to {@code DEPENDENCY_UNAVAILABLE} now get that answer at once instead of after the 3 s
 * read timeout. Only I/O errors (timeouts, refused connections) and 5xx responses count as
 * failures; a 4xx is the dependency answering correctly (out of stock, expired coupon...).
 */
public final class DependencyBreakerCapability implements Capability {

  private final CircuitBreakerRegistry registry;

  public DependencyBreakerCapability(CircuitBreakerRegistry registry) {
    this.registry = registry;
  }

  /** Defaults: open at 50% failures over the last 20 calls (min 10), probe again after 10 s. */
  public static CircuitBreakerRegistry defaultRegistry() {
    return CircuitBreakerRegistry.of(
        CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(20)
            .minimumNumberOfCalls(10)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .permittedNumberOfCallsInHalfOpenState(3)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .build());
  }

  @Override
  public Client enrich(Client client) {
    return (request, options) -> execute(client, request, options);
  }

  private Response execute(Client client, Request request, Request.Options options)
      throws IOException {
    String host = hostOf(request.url());
    if (host == null) {
      return client.execute(request, options);
    }
    CircuitBreaker breaker = registry.circuitBreaker("dep-" + host);
    if (!breaker.tryAcquirePermission()) {
      throw new IOException("Circuit open for " + host + ": failing fast");
    }
    long start = System.nanoTime();
    try {
      Response response = client.execute(request, options);
      long took = System.nanoTime() - start;
      if (response.status() >= 500) {
        breaker.onError(took, TimeUnit.NANOSECONDS, new IOException("HTTP " + response.status()));
      } else {
        breaker.onSuccess(took, TimeUnit.NANOSECONDS);
      }
      return response;
    } catch (IOException | RuntimeException e) {
      breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
      throw e;
    }
  }

  private static String hostOf(String url) {
    try {
      return URI.create(url).getHost();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
