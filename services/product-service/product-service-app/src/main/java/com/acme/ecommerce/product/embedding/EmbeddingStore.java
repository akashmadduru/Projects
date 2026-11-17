package com.acme.ecommerce.product.embedding;

import com.acme.ecommerce.product.embedding.EmbeddingRepository.Candidate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transaction boundaries of the embedding pipeline.
 *
 * <p>Deliberately tiny and separate from {@link EmbeddingReconciler}: the reconciler's provider
 * calls must run with NO transaction (and so no pooled connection) open, which a single
 * {@code @Transactional} method around the whole loop could not express. Each method here is one
 * short transaction.
 */
@Service
@RequiredArgsConstructor
public class EmbeddingStore {

  /** One freshly computed vector ready to persist. */
  public record Embedded(
      UUID productId, float[] vector, String contentHash, Instant sourceUpdatedAt) {}

  /** One failed attempt to record. */
  public record Failure(UUID productId, String contentHash, String error) {}

  private final EmbeddingRepository repository;

  @Transactional(readOnly = true)
  public List<Candidate> loadCandidates(String model, Instant now, Instant sweepBefore, int limit) {
    return repository.findCandidates(model, now, sweepBefore, limit);
  }

  /**
   * Persists a batch outcome atomically.
   *
   * @param unchanged unchanged products, each with the {@code updated_at} that was read
   * @param checkedAt when the batch was verified (drives the full sweep only)
   */
  @Transactional
  public void persist(
      String model,
      List<Embedded> embedded,
      java.util.Map<UUID, Instant> unchanged,
      Instant checkedAt,
      Instant now) {
    for (Embedded e : embedded) {
      repository.upsert(
          e.productId(), e.vector(), model, e.contentHash(), now, checkedAt, e.sourceUpdatedAt());
    }
    repository.touchChecked(unchanged, checkedAt);
    repository.clearFailures(embedded.stream().map(Embedded::productId).toList());
  }

  @Transactional
  public void recordFailures(List<Failure> failures, Instant now, Duration base, Duration max) {
    for (Failure f : failures) {
      repository.recordFailure(f.productId(), f.contentHash(), f.error(), now, base, max);
    }
  }
}
