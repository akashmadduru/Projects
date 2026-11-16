package com.acme.ecommerce.product.embedding;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.product.embedding.EmbeddingRepository.Coverage;
import com.acme.ecommerce.product.repository.ProductRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin view of, and control over, the embedding index. */
@Service
public class EmbeddingAdminService {

  /** Coverage of the active catalog. {@code enabled=false} means no provider is configured. */
  public record Status(
      boolean enabled,
      String provider,
      String model,
      int dims,
      long active,
      long embedded,
      long stale,
      long failed,
      Instant lastRunAt,
      Instant oldestStaleAt) {}

  private final ObjectProvider<EmbeddingPort> port;
  private final EmbeddingRepository repository;
  private final ProductRepository products;
  private final EmbeddingProperties properties;

  public EmbeddingAdminService(
      ObjectProvider<EmbeddingPort> port,
      EmbeddingRepository repository,
      ProductRepository products,
      EmbeddingProperties properties) {
    this.port = port;
    this.repository = repository;
    this.products = products;
    this.properties = properties;
  }

  @Transactional(readOnly = true)
  public Status status() {
    EmbeddingPort embeddingPort = port.getIfAvailable();
    String model = embeddingPort == null ? "" : embeddingPort.modelId();
    Coverage coverage = repository.coverage(model);
    return new Status(
        embeddingPort != null,
        properties.getProvider(),
        embeddingPort == null ? null : model,
        embeddingPort == null ? EmbeddingSchema.DIMS : embeddingPort.dims(),
        coverage.active(),
        coverage.embedded(),
        coverage.stale(),
        coverage.failed(),
        coverage.lastRunAt(),
        coverage.oldestStaleAt());
  }

  /**
   * Flags embeddings for re-embedding; the reconciler does the work on its next run.
   *
   * @param productId one product, or null for the whole catalog
   * @return how many products are queued
   * @throws AiUnavailableException when no provider is configured (nothing would ever process it)
   * @throws ResourceNotFoundException for an unknown product id
   */
  @Transactional
  public long reindex(UUID productId) {
    EmbeddingPort embeddingPort = port.getIfAvailable();
    if (embeddingPort == null) {
      throw new AiUnavailableException(
          "Semantic search is disabled: no embedding provider is configured");
    }
    if (productId == null) {
      repository.invalidate(null);
      return repository.coverage(embeddingPort.modelId()).active();
    }
    var product =
        products
            .findById(productId)
            .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
    repository.invalidate(productId);
    return product.isActive() ? 1L : 0L;
  }
}
