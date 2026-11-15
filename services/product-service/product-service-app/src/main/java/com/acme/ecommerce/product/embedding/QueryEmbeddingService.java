package com.acme.ecommerce.product.embedding;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;

/**
 * Embeds search queries: cache first, then the provider behind the {@code embeddingCb} breaker.
 *
 * <p>THE CONTRACT IS "EMPTY MEANS DEGRADE". Provider disabled, provider slow, provider failing or
 * breaker open - every one of those yields {@link Optional#empty()} and the caller falls back to
 * keyword search. Nothing here ever throws to the request, and nothing ever invents a vector.
 *
 * <p>Never call this inside a database transaction: the provider call can take up to the query
 * timeout and would otherwise pin a pooled connection for it.
 */
@Slf4j
@Service
public class QueryEmbeddingService {

  /** The breaker's name; its settings live under {@code resilience4j.circuitbreaker.instances}. */
  static final String CIRCUIT_BREAKER_NAME = "embeddingCb";

  private final ObjectProvider<EmbeddingPort> port;
  private final QueryEmbeddingCache cache;
  private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;
  private final MeterRegistry meters;

  public QueryEmbeddingService(
      ObjectProvider<EmbeddingPort> port,
      QueryEmbeddingCache cache,
      CircuitBreakerFactory<?, ?> circuitBreakerFactory,
      MeterRegistry meters) {
    this.port = port;
    this.cache = cache;
    this.circuitBreakerFactory = circuitBreakerFactory;
    this.meters = meters;
  }

  /** {@code true} when an embedding provider is configured. */
  public boolean enabled() {
    return port.getIfAvailable() != null;
  }

  /** The configured model id, if any. */
  public Optional<String> modelId() {
    return Optional.ofNullable(port.getIfAvailable()).map(EmbeddingPort::modelId);
  }

  /**
   * The vector for an already-normalised query.
   *
   * @return the vector, or empty when the feature is disabled or the provider is unavailable
   */
  public Optional<float[]> embed(String normalizedQuery) {
    EmbeddingPort embeddingPort = port.getIfAvailable();
    if (embeddingPort == null) {
      return Optional.empty();
    }
    String key = embeddingPort.modelId() + "|" + normalizedQuery;
    Optional<float[]> cached = cache.get(key);
    if (cached.isPresent()) {
      meters.counter("embedding.query.cache", "result", "hit").increment();
      return cached;
    }
    meters.counter("embedding.query.cache", "result", "miss").increment();
    float[] vector =
        circuitBreakerFactory
            .create(CIRCUIT_BREAKER_NAME)
            .run(
                () -> embeddingPort.embed(List.of(normalizedQuery), EmbedKind.QUERY).get(0),
                throwable -> {
                  // Class name only: the message may echo provider detail, and the query text
                  // itself must never reach a log line.
                  log.warn(
                      "Query embedding unavailable, degrading to keyword search: {}",
                      throwable.getClass().getSimpleName());
                  meters.counter("embedding.query.failures").increment();
                  return null;
                });
    if (vector == null) {
      return Optional.empty();
    }
    cache.put(key, vector);
    return Optional.of(vector);
  }
}
