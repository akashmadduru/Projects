package com.acme.ecommerce.product.embedding;

import java.util.List;

/**
 * The only thing product-service knows about an embedding provider.
 *
 * <p>Provider-agnostic on purpose: domain and search code never import an AWS (or any other vendor)
 * type. Adapters live in {@code embedding.adapter}. The bean is ABSENT when no provider is
 * configured ({@code product.embedding.provider=none}, the default) - callers treat "no bean" as
 * "feature explicitly disabled" and degrade to keyword search; nothing ever substitutes fake
 * vectors.
 *
 * <p>Implementations are called OUTSIDE any database transaction (the Hikari pool is small) and
 * must be thread-safe.
 */
public interface EmbeddingPort {

  /** Stable identifier of the model; stored with every vector so a model change is detectable. */
  String modelId();

  /** Dimension of every vector this port returns. Must equal the {@code vector(N)} column. */
  int dims();

  /**
   * Embeds the texts, preserving order.
   *
   * @param texts the texts to embed (never empty)
   * @param kind whether these are queries or documents
   * @return one vector of {@link #dims()} finite floats per text
   * @throws com.acme.ecommerce.common.core.error.UpstreamUnavailableException if the provider
   *     failed, timed out or throttled; all-or-nothing, no partial results
   */
  List<float[]> embed(List<String> texts, EmbedKind kind);
}
