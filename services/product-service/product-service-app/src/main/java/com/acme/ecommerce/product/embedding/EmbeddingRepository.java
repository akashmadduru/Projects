package com.acme.ecommerce.product.embedding;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Native SQL over {@code product.product_embeddings} and {@code product.embedding_failures}.
 *
 * <p>EVERY pgvector type and operator is schema-qualified ({@code public.vector}, {@code
 * OPERATOR(public.<=>)}) rather than relying on {@code search_path}: Flyway runs with {@code
 * search_path=product} and the runtime role's default is {@code public}, so an unqualified name
 * works in one and not the other. The extension lives in {@code public} because it is installed by
 * the bootstrap superuser (it is not a "trusted" extension) before Flyway ever creates the {@code
 * product} schema.
 *
 * <p>No transaction annotations here - callers are services ({@code EmbeddingStore}, {@code
 * EmbeddingAdminService}) that own the transaction boundary.
 */
@Repository
public class EmbeddingRepository {

  /** A product whose embedding is missing or must be re-verified. */
  public record Candidate(
      UUID productId,
      String name,
      String brand,
      String categoryName,
      String description,
      String attributesJson,
      String specificationsJson,
      String storedHash,
      String storedModel,
      /* products.updated_at as read: stored on the row as source_updated_at once processed. */
      Instant updatedAt) {}

  /** Coverage figures for the admin badge. */
  public record Coverage(
      long active,
      long embedded,
      long stale,
      long failed,
      Instant lastRunAt,
      Instant oldestStaleAt) {}

  @PersistenceContext private EntityManager em;

  /**
   * Active products needing work, never-embedded first, then least recently verified.
   *
   * <p>A product is a candidate when it has no embedding, was embedded with another model, was
   * edited after the version last verified ({@code updated_at > source_updated_at} - the cheap
   * prefilter), or has not been verified since {@code sweepBefore} (the daily full sweep: category
   * renames do not bump {@code products.updated_at}). Products whose last attempt failed are
   * skipped until their backoff elapses.
   */
  @SuppressWarnings("unchecked")
  public List<Candidate> findCandidates(String model, Instant now, Instant sweepBefore, int limit) {
    List<Object[]> rows =
        em.createNativeQuery(
                "select p.id, p.name, p.brand, c.name, p.description, "
                    + "cast(p.attributes as text), cast(p.specifications as text), "
                    + "e.content_hash, e.model, p.updated_at "
                    + "from product.products p "
                    + "left join product.categories c on c.id = p.category_id "
                    + "left join product.product_embeddings e on e.product_id = p.id "
                    + "where p.active "
                    + "and (e.product_id is null or e.model <> :model "
                    + "     or p.updated_at > e.source_updated_at or e.checked_at < :sweepBefore) "
                    + "and not exists (select 1 from product.embedding_failures f "
                    + "                where f.product_id = p.id and f.next_attempt_at > :now) "
                    + "order by coalesce(e.checked_at, timestamptz 'epoch'), p.id "
                    + "limit :lim")
            .setParameter("model", model)
            .setParameter("sweepBefore", sweepBefore)
            .setParameter("now", now)
            .setParameter("lim", limit)
            .getResultList();
    List<Candidate> out = new ArrayList<>(rows.size());
    for (Object[] r : rows) {
      out.add(
          new Candidate(
              asUuid(r[0]),
              (String) r[1],
              (String) r[2],
              (String) r[3],
              (String) r[4],
              (String) r[5],
              (String) r[6],
              r[7] == null ? null : ((String) r[7]).strip(),
              (String) r[8],
              asInstant(r[9])));
    }
    return out;
  }

  /**
   * Inserts or replaces the vector. A product deleted since it was read simply inserts nothing
   * (INSERT ... SELECT), instead of failing the whole batch on the foreign key.
   *
   * @param checkedAt when the product was verified (drives the full sweep only)
   * @param sourceUpdatedAt the {@code products.updated_at} that was read: an edit landing between
   *     read and write is newer and is picked up next run
   */
  public void upsert(
      UUID productId,
      float[] vector,
      String model,
      String contentHash,
      Instant embeddedAt,
      Instant checkedAt,
      Instant sourceUpdatedAt) {
    em.createNativeQuery(
            "insert into product.product_embeddings "
                + "(product_id, embedding, model, dims, content_hash, embedded_at, checked_at,"
                + " source_updated_at) "
                + "select p.id, cast(:vec as public.vector), :model, :dims, :hash, :embeddedAt,"
                + " :checkedAt, :sourceUpdatedAt "
                + "from product.products p where p.id = :id "
                + "on conflict (product_id) do update set embedding = excluded.embedding, "
                + "model = excluded.model, dims = excluded.dims, content_hash = excluded.content_hash, "
                + "embedded_at = excluded.embedded_at, checked_at = excluded.checked_at, "
                + "source_updated_at = excluded.source_updated_at")
        .setParameter("id", productId)
        .setParameter("vec", toLiteral(vector))
        .setParameter("model", model)
        .setParameter("dims", vector.length)
        .setParameter("hash", contentHash)
        .setParameter("embeddedAt", embeddedAt)
        .setParameter("checkedAt", checkedAt)
        .setParameter("sourceUpdatedAt", sourceUpdatedAt)
        .executeUpdate();
  }

  /**
   * Marks unchanged products as verified without any provider call, each at the product version
   * that was read (at most one batch, so one statement per row is fine).
   */
  public void touchChecked(java.util.Map<UUID, Instant> sourceVersions, Instant checkedAt) {
    for (var entry : sourceVersions.entrySet()) {
      em.createNativeQuery(
              "update product.product_embeddings set checked_at = :checkedAt, "
                  + "source_updated_at = :sourceUpdatedAt where product_id = :id")
          .setParameter("checkedAt", checkedAt)
          .setParameter("sourceUpdatedAt", entry.getValue())
          .setParameter("id", entry.getKey())
          .executeUpdate();
    }
  }

  public void clearFailures(Collection<UUID> productIds) {
    if (productIds.isEmpty()) {
      return;
    }
    em.createNativeQuery("delete from product.embedding_failures where product_id in (:ids)")
        .setParameter("ids", productIds)
        .executeUpdate();
  }

  /** Records a failed attempt; the retry delay doubles per attempt, capped at {@code max}. */
  public void recordFailure(
      UUID productId, String contentHash, String error, Instant now, Duration base, Duration max) {
    em.createNativeQuery(
            "insert into product.embedding_failures "
                + "(product_id, content_hash, attempts, last_error, next_attempt_at) "
                + "select p.id, :hash, 1, :error, "
                + "cast(:now as timestamptz) + make_interval(secs => cast(:baseSecs as double precision)) "
                + "from product.products p where p.id = :id "
                + "on conflict (product_id) do update set content_hash = excluded.content_hash, "
                + "attempts = embedding_failures.attempts + 1, last_error = excluded.last_error, "
                + "next_attempt_at = cast(:now as timestamptz) + make_interval(secs => "
                + "least(cast(:maxSecs as double precision), "
                + "cast(:baseSecs as double precision) * power(2, embedding_failures.attempts)))")
        .setParameter("id", productId)
        .setParameter("hash", contentHash)
        .setParameter("error", error)
        .setParameter("now", now)
        .setParameter("baseSecs", base.toSeconds())
        .setParameter("maxSecs", max.toSeconds())
        .executeUpdate();
  }

  /**
   * Forces re-embedding: zeroes the stored hash and the verification time, but KEEPS the vector so
   * search stays semantic while the reconciler catches up.
   *
   * @param productId one product, or null for all
   * @return rows invalidated
   */
  public int invalidate(UUID productId) {
    String sql =
        "update product.product_embeddings set content_hash = repeat('0', 64), "
            + "checked_at = timestamptz 'epoch', source_updated_at = timestamptz 'epoch'";
    if (productId == null) {
      em.createNativeQuery("delete from product.embedding_failures").executeUpdate();
      return em.createNativeQuery(sql).executeUpdate();
    }
    em.createNativeQuery("delete from product.embedding_failures where product_id = :id")
        .setParameter("id", productId)
        .executeUpdate();
    return em.createNativeQuery(sql + " where product_id = :id")
        .setParameter("id", productId)
        .executeUpdate();
  }

  /** Coverage of ACTIVE products by the given model. */
  public Coverage coverage(String model) {
    Object[] r =
        (Object[])
            em.createNativeQuery(
                    "select "
                        + "(select count(*) from product.products where active), "
                        + "(select count(*) from product.products p join product.product_embeddings e "
                        + "   on e.product_id = p.id where p.active and e.model = :model), "
                        + "(select count(*) from product.products p left join product.product_embeddings e "
                        + "   on e.product_id = p.id and e.model = :model "
                        + "   where p.active and (e.product_id is null or p.updated_at > e.source_updated_at)), "
                        + "(select count(*) from product.embedding_failures f join product.products p "
                        + "   on p.id = f.product_id where p.active), "
                        + "(select max(checked_at) from product.product_embeddings), "
                        + "(select min(p.updated_at) from product.products p left join "
                        + "   product.product_embeddings e on e.product_id = p.id and e.model = :model "
                        + "   where p.active and (e.product_id is null or p.updated_at > e.source_updated_at))")
                .setParameter("model", model)
                .getSingleResult();
    return new Coverage(
        ((Number) r[0]).longValue(),
        ((Number) r[1]).longValue(),
        ((Number) r[2]).longValue(),
        ((Number) r[3]).longValue(),
        asInstant(r[4]),
        asInstant(r[5]));
  }

  /** {@code [v1,v2,...]} - pgvector's text input format, bound as a string and cast in SQL. */
  public static String toLiteral(float[] vector) {
    StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
    for (int i = 0; i < vector.length; i++) {
      if (!Float.isFinite(vector[i])) {
        throw new IllegalArgumentException("non-finite component at index " + i);
      }
      if (i > 0) {
        sb.append(',');
      }
      sb.append(vector[i]);
    }
    return sb.append(']').toString();
  }

  private static UUID asUuid(Object value) {
    return value instanceof UUID u ? u : UUID.fromString(value.toString());
  }

  private static Instant asInstant(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Instant i) {
      return i;
    }
    if (value instanceof java.time.OffsetDateTime o) {
      return o.toInstant();
    }
    if (value instanceof java.sql.Timestamp t) {
      return t.toInstant();
    }
    throw new IllegalStateException("unexpected timestamp type " + value.getClass());
  }
}
