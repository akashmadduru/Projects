package com.acme.ecommerce.product.search;

import com.acme.ecommerce.product.embedding.EmbeddingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Executes the hybrid ranking statement and the brand lookup. No transaction annotations: {@code
 * HybridSearchService} wraps {@link #fuse} in one read-only transaction because the {@code
 * set_config(.., true)} session settings it issues first are transaction-local.
 */
@Repository
public class HybridSearchRepository implements QueryUnderstanding.BrandSource {

  /** One fused candidate. */
  public record Fused(UUID id, double score, boolean inKeyword, boolean inVector) {}

  /** Everything {@link #fuse} needs, so the signature stays readable. */
  public record FuseRequest(
      String text,
      boolean keywordLeg,
      float[] queryVector,
      String model,
      SearchFilters filters,
      SearchSort sort,
      int rrfK,
      int topN,
      double maxDistance,
      double trigramThreshold,
      int hnswEfSearch) {}

  @PersistenceContext private EntityManager em;

  /**
   * Runs the fused query. Each leg is included only when asked for: the keyword leg per {@code
   * keywordLeg}, the vector leg only when {@code queryVector} is present. Must be called inside a
   * (read-only) transaction.
   */
  @SuppressWarnings("unchecked")
  public List<Fused> fuse(FuseRequest request) {
    boolean vector = request.queryVector() != null;
    ProductFilterSql.Fragment filter = ProductFilterSql.of(request.filters());
    String sql = HybridSearchSql.build(request.keywordLeg(), vector, filter.sql(), request.sort());

    setLocal("pg_trgm.similarity_threshold", Double.toString(request.trigramThreshold()));
    if (vector) {
      setLocal("hnsw.iterative_scan", "relaxed_order");
      setLocal("hnsw.ef_search", Integer.toString(request.hnswEfSearch()));
    }

    Query query = em.createNativeQuery(sql);
    if (request.keywordLeg()) {
      query.setParameter("q", request.text());
      query.setParameter("lq", request.text().toLowerCase(java.util.Locale.ROOT));
    }
    query.setParameter("k", request.rrfK());
    query.setParameter("topN", request.topN());
    if (vector) {
      query.setParameter("qvec", EmbeddingRepository.toLiteral(request.queryVector()));
      query.setParameter("model", request.model());
      query.setParameter("maxDist", request.maxDistance());
    }
    for (Map.Entry<String, Object> param : filter.params().entrySet()) {
      query.setParameter(param.getKey(), param.getValue());
    }

    List<Object[]> rows = query.getResultList();
    List<Fused> out = new ArrayList<>(rows.size());
    for (Object[] r : rows) {
      out.add(
          new Fused(
              r[0] instanceof UUID u ? u : UUID.fromString(r[0].toString()),
              ((Number) r[1]).doubleValue(),
              Boolean.TRUE.equals(r[2]),
              Boolean.TRUE.equals(r[3])));
    }
    return out;
  }

  /** Distinct, non-blank brands of active products. */
  @Override
  @SuppressWarnings("unchecked")
  public Collection<String> get() {
    return em.createNativeQuery(
            "select distinct brand from product.products "
                + "where active and brand is not null and brand <> ''")
        .getResultList();
  }

  private void setLocal(String name, String value) {
    em.createNativeQuery("select set_config(:name, :value, true)")
        .setParameter("name", name)
        .setParameter("value", value)
        .getSingleResult();
  }
}
