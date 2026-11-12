package com.acme.ecommerce.product.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Postgres-specific queries (full-text, trigram, co-view) that the Criteria API cannot express.
 * pg_trgm lives in the product schema (see V6), hence the product.similarity(...) qualification.
 */
@Repository
public class ProductNativeQueries {

  private static final int MAX_SEARCH_IDS = 2000;

  @PersistenceContext private EntityManager em;

  /** Product ids matching the query (FTS, trigram or substring), best match first. */
  @SuppressWarnings("unchecked")
  public List<UUID> rankedIds(String q) {
    String lower = q.trim().toLowerCase(Locale.ROOT);
    String pattern =
        "%" + lower.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    List<Object> rows =
        em.createNativeQuery(
                "select p.id from product.products p "
                    + "where p.search_vector @@ websearch_to_tsquery('english', :q) "
                    + "or product.similarity(lower(p.name), :lq) > 0.25 "
                    + "or lower(p.name) like :pat escape '\\' or lower(p.sku) like :pat escape '\\' "
                    + "order by (ts_rank(p.search_vector, websearch_to_tsquery('english', :q)) "
                    + "+ product.similarity(lower(p.name), :lq)) desc, p.name "
                    + "limit "
                    + MAX_SEARCH_IDS)
            .setParameter("q", q.trim())
            .setParameter("lq", lower)
            .setParameter("pat", pattern)
            .getResultList();
    List<UUID> ids = new ArrayList<>(rows.size());
    for (Object row : rows) {
      ids.add(row instanceof UUID u ? u : UUID.fromString(row.toString()));
    }
    return ids;
  }

  /** Best vocabulary word (from product names) for a possibly misspelt word, or null. */
  public String closestWord(String word) {
    List<?> rows =
        em.createNativeQuery(
                "select w from (select distinct unnest(string_to_array(lower("
                    + "regexp_replace(name, '[^a-zA-Z0-9]+', ' ', 'g')), ' ')) as w "
                    + "from product.products) t where length(w) > 2 "
                    + "and product.similarity(w, :w) > 0.3 "
                    + "order by product.similarity(w, :w) desc, w limit 1")
            .setParameter("w", word.toLowerCase(Locale.ROOT))
            .getResultList();
    return rows.isEmpty() ? null : rows.get(0).toString();
  }

  /** Active categories whose name contains the text: rows of {id, name}. */
  @SuppressWarnings("unchecked")
  public List<Object[]> categoriesMatching(String q, int limit) {
    String pattern = "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
    return em.createNativeQuery(
            "select id, name from product.categories where is_active "
                + "and lower(name) like :pat order by name limit :lim")
        .setParameter("pat", pattern)
        .setParameter("lim", limit)
        .getResultList();
  }

  public void recordView(String sessionId, UUID productId) {
    em.createNativeQuery(
            "insert into product.product_views (session_id, product_id) values (:s, :p)")
        .setParameter("s", sessionId)
        .setParameter("p", productId)
        .executeUpdate();
  }

  /** Products co-viewed in the same sessions as the given one, most co-viewed first. */
  @SuppressWarnings("unchecked")
  public List<UUID> alsoViewedIds(UUID productId, int limit) {
    List<Object> rows =
        em.createNativeQuery(
                "select v2.product_id from product.product_views v1 "
                    + "join product.product_views v2 on v1.session_id = v2.session_id "
                    + "and v2.product_id <> v1.product_id "
                    + "join product.products p on p.id = v2.product_id and p.active "
                    + "where v1.product_id = :id group by v2.product_id "
                    + "order by count(distinct v1.session_id) desc limit :lim")
            .setParameter("id", productId)
            .setParameter("lim", limit)
            .getResultList();
    List<UUID> ids = new ArrayList<>();
    for (Object row : rows) {
      ids.add(row instanceof UUID u ? u : UUID.fromString(row.toString()));
    }
    return ids;
  }

  /** Same-category active products by rating, excluding the given ids. */
  @SuppressWarnings("unchecked")
  public List<UUID> sameCategoryIds(UUID productId, List<UUID> exclude, int limit) {
    List<UUID> excl = new ArrayList<>(exclude);
    excl.add(productId);
    List<Object> rows =
        em.createNativeQuery(
                "select p.id from product.products p, "
                    + "(select category_id from product.products where id = :id) c "
                    + "where p.category_id = c.category_id and p.active and p.id not in (:excl) "
                    + "order by p.average_rating desc nulls last, p.name limit :lim")
            .setParameter("id", productId)
            .setParameter("excl", excl)
            .setParameter("lim", limit)
            .getResultList();
    List<UUID> ids = new ArrayList<>();
    for (Object row : rows) {
      ids.add(row instanceof UUID u ? u : UUID.fromString(row.toString()));
    }
    return ids;
  }

  public int deleteViewsOlderThanDays(int days) {
    return em.createNativeQuery(
            "delete from product.product_views where viewed_at < now() - make_interval(days => :d)")
        .setParameter("d", days)
        .executeUpdate();
  }
}
