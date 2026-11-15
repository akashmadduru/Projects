package com.acme.ecommerce.product.embedding;

import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.product.embedding.EmbeddingRepository.Candidate;
import com.acme.ecommerce.product.embedding.EmbeddingStore.Embedded;
import com.acme.ecommerce.product.embedding.EmbeddingStore.Failure;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code product_embeddings} in step with the catalog by polling, not by events.
 *
 * <p>WHY POLLING. Product writes are not outboxed (a Kafka publish is best-effort after commit), so
 * an event-driven indexer would silently drift on the first lost message. Reading the database is
 * the one mechanism that covers every write path - {@code ProductCatalogService}, {@code
 * SellerCatalogService}, seeds, raw SQL - with no hook in any of them.
 *
 * <p>HOW. Each round: (1) a short read-only transaction selects up to {@code batchSize} candidates
 * (see {@link EmbeddingRepository#findCandidates}); (2) with NO transaction and NO connection held,
 * each is hashed - unchanged ones are only re-stamped, changed ones go to the provider; (3) a short
 * write transaction stores the vectors. The advisory {@link JobLock} makes one replica run it at a
 * time. A provider outage records a per-product failure with exponential backoff and ends the run.
 *
 * <p>SELF-HEALING. Rows are stamped with the snapshot time taken BEFORE the read, so a product
 * edited between read and write still has {@code updated_at > checked_at} and is redone next run.
 * Once per {@code fullSweepInterval} every product is re-hashed regardless (a category rename does
 * not touch {@code products.updated_at}).
 */
@Slf4j
@Component
public class EmbeddingReconciler {

  static final String LOCK_NAME = "embedding-reconciler";

  /** Margin subtracted from the snapshot time to absorb small clock skew between replicas. */
  private static final Duration SKEW = Duration.ofSeconds(5);

  /** Stop the run after this many back-to-back provider failures: the provider is down. */
  private static final int MAX_CONSECUTIVE_FAILURES = 3;

  private static final int MAX_ERROR_CHARS = 500;

  /** Result of one run, for logs and tests. */
  public record Result(int embedded, int unchanged, int failed, int batches) {}

  private final ObjectProvider<EmbeddingPort> port;
  private final EmbeddingStore store;
  private final EmbeddingProperties properties;
  private final TimeProvider time;
  private final JobLock jobLock;
  private final MeterRegistry meters;

  public EmbeddingReconciler(
      ObjectProvider<EmbeddingPort> port,
      EmbeddingStore store,
      EmbeddingProperties properties,
      TimeProvider time,
      JobLock jobLock,
      MeterRegistry meters) {
    this.port = port;
    this.store = store;
    this.properties = properties;
    this.time = time;
    this.jobLock = jobLock;
    this.meters = meters;
  }

  /** The scheduled entry point. A no-op when the feature or the reconciler is disabled. */
  @Scheduled(
      initialDelayString = "${product.embedding.reconciler.initial-delay:PT30S}",
      fixedDelayString = "${product.embedding.reconciler.interval:PT1M}")
  public void scheduledRun() {
    EmbeddingPort embeddingPort = port.getIfAvailable();
    if (embeddingPort == null || !properties.getReconciler().isEnabled()) {
      return;
    }
    try {
      jobLock.runExclusive(LOCK_NAME, () -> reconcile(embeddingPort));
    } catch (RuntimeException e) {
      log.warn("Embedding reconciler run failed: {}", e.toString());
    }
  }

  /** Runs one bounded reconciliation pass. Public so tests and the admin path can drive it. */
  public Result reconcile(EmbeddingPort embeddingPort) {
    EmbeddingProperties.Reconciler cfg = properties.getReconciler();
    String model = embeddingPort.modelId();
    int embedded = 0;
    int unchanged = 0;
    int failed = 0;
    int batches = 0;
    int consecutiveFailures = 0;

    while (batches < cfg.getMaxBatchesPerRun() && consecutiveFailures < MAX_CONSECUTIVE_FAILURES) {
      Instant snapshot = time.now().minus(SKEW);
      Instant now = time.now();
      List<Candidate> candidates =
          store.loadCandidates(
              model, now, now.minus(cfg.getFullSweepInterval()), cfg.getBatchSize());
      if (candidates.isEmpty()) {
        break;
      }
      batches++;

      List<UUID> unchangedIds = new ArrayList<>();
      List<Pending> pending = new ArrayList<>();
      for (Candidate c : candidates) {
        String text =
            EmbeddingText.build(
                c.name(),
                c.brand(),
                c.categoryName(),
                c.description(),
                c.attributesJson(),
                c.specificationsJson());
        String hash = EmbeddingText.hash(text, model);
        if (hash.equals(c.storedHash()) && model.equals(c.storedModel())) {
          unchangedIds.add(c.productId());
        } else {
          pending.add(new Pending(c.productId(), text, hash));
        }
      }

      // --- provider calls: no transaction, no connection held --------------------------------
      List<Embedded> done = new ArrayList<>();
      List<Failure> failures = new ArrayList<>();
      consecutiveFailures = embedAll(embeddingPort, pending, done, failures, consecutiveFailures);

      store.persist(model, done, unchangedIds, snapshot, time.now());
      if (!failures.isEmpty()) {
        store.recordFailures(failures, time.now(), cfg.getBackoffBase(), cfg.getBackoffMax());
      }
      embedded += done.size();
      unchanged += unchangedIds.size();
      failed += failures.size();
    }

    meters.counter("embedding.reconcile", "outcome", "embedded").increment(embedded);
    meters.counter("embedding.reconcile", "outcome", "unchanged").increment(unchanged);
    meters.counter("embedding.reconcile", "outcome", "failed").increment(failed);
    if (embedded + failed > 0) {
      log.info(
          "Embedding reconcile: embedded={} unchanged={} failed={} batches={}",
          embedded,
          unchanged,
          failed,
          batches);
    }
    return new Result(embedded, unchanged, failed, batches);
  }

  /**
   * Embeds the pending texts as one provider batch; if that fails, retries them one by one so a
   * single poisonous text cannot starve its neighbours. Stops early once the provider looks down.
   *
   * @return the updated count of consecutive failures
   */
  private int embedAll(
      EmbeddingPort embeddingPort,
      List<Pending> pending,
      List<Embedded> done,
      List<Failure> failures,
      int consecutiveFailures) {
    if (pending.isEmpty()) {
      return 0;
    }
    try {
      List<float[]> vectors =
          embeddingPort.embed(pending.stream().map(Pending::text).toList(), EmbedKind.DOCUMENT);
      for (int i = 0; i < pending.size(); i++) {
        done.add(new Embedded(pending.get(i).id(), vectors.get(i), pending.get(i).hash()));
      }
      return 0;
    } catch (RuntimeException batchError) {
      if (pending.size() == 1) {
        failures.add(failure(pending.get(0), batchError));
        return consecutiveFailures + 1;
      }
    }
    int streak = consecutiveFailures;
    for (Pending p : pending) {
      if (streak >= MAX_CONSECUTIVE_FAILURES) {
        break;
      }
      try {
        float[] vector = embeddingPort.embed(List.of(p.text()), EmbedKind.DOCUMENT).get(0);
        done.add(new Embedded(p.id(), vector, p.hash()));
        streak = 0;
      } catch (RuntimeException e) {
        failures.add(failure(p, e));
        streak++;
      }
    }
    return streak;
  }

  private static Failure failure(Pending p, RuntimeException e) {
    String message = e.getClass().getSimpleName();
    if (e.getCause() != null) {
      message += " <- " + e.getCause().getClass().getSimpleName();
    }
    return new Failure(
        p.id(),
        p.hash(),
        message.length() > MAX_ERROR_CHARS ? message.substring(0, MAX_ERROR_CHARS) : message);
  }

  private record Pending(UUID id, String text, String hash) {}
}
