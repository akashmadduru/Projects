package com.acme.ecommerce.product.repository;

import com.acme.ecommerce.product.repository.ProductFilterNativeSql.Fragment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Facet aggregates in the database: one grouped query per facet instead of loading every matching
 * product into Java. Values that differ only in case are one bucket (labelled with the smallest
 * spelling), matching the case-insensitive brand and attribute filters.
 */
@Repository
public class ProductFacetQueries {

  /** One facet bucket. {@code key} is null for brands. */
  public record Bucket(String key, String value, long count) {}

  /** Min/max price; both null when nothing matches. */
  public record Range(BigDecimal min, BigDecimal max) {}

  /** Never parse a non-object (array, scalar) as key/value pairs: jsonb_each_text would throw. */
  private static final String ATTRIBUTE_PAIRS =
      "cross join lateral jsonb_each_text(case when jsonb_typeof(p.attributes) = 'object'"
          + " then p.attributes else cast('{}' as jsonb) end) kv";

  @PersistenceContext private EntityManager em;

  public List<Bucket> brands(Fragment where) {
    List<Bucket> out = new ArrayList<>();
    for (Object[] row :
        rows(
            "select min(p.brand), count(*) from product.products p where "
                + where.sql()
                + " and p.brand is not null and btrim(p.brand) <> '' group by lower(p.brand)",
            where)) {
      out.add(new Bucket(null, row[0].toString(), ((Number) row[1]).longValue()));
    }
    return out;
  }

  /**
   * Attribute buckets.
   *
   * @param onlyKey when non-null, only this key (counted without its own filter by the caller)
   * @param exceptKeys keys to leave out (they are counted separately, each without its filter)
   */
  public List<Bucket> attributes(Fragment where, String onlyKey, Collection<String> exceptKeys) {
    StringBuilder sql =
        new StringBuilder("select kv.key, min(kv.value), count(*) from product.products p ")
            .append(ATTRIBUTE_PAIRS)
            .append(" where ")
            .append(where.sql())
            .append(" and kv.value is not null");
    if (onlyKey != null) {
      sql.append(" and kv.key = :facetKey");
    }
    if (exceptKeys != null && !exceptKeys.isEmpty()) {
      sql.append(" and kv.key not in (:facetExcept)");
    }
    sql.append(" group by kv.key, lower(kv.value)");
    Query query = em.createNativeQuery(sql.toString());
    where.params().forEach(query::setParameter);
    if (onlyKey != null) {
      query.setParameter("facetKey", onlyKey);
    }
    if (exceptKeys != null && !exceptKeys.isEmpty()) {
      query.setParameter("facetExcept", List.copyOf(exceptKeys));
    }
    List<Bucket> out = new ArrayList<>();
    for (Object[] row : cast(query.getResultList())) {
      out.add(new Bucket(row[0].toString(), row[1].toString(), ((Number) row[2]).longValue()));
    }
    return out;
  }

  public Range price(Fragment where) {
    List<Object[]> rows =
        rows(
            "select min(p.price_amount), max(p.price_amount) from product.products p where "
                + where.sql(),
            where);
    Object[] row = rows.isEmpty() ? new Object[2] : rows.get(0);
    return new Range(decimal(row[0]), decimal(row[1]));
  }

  private List<Object[]> rows(String sql, Fragment where) {
    Query query = em.createNativeQuery(sql);
    where.params().forEach(query::setParameter);
    return cast(query.getResultList());
  }

  @SuppressWarnings("unchecked")
  private static List<Object[]> cast(List<?> rows) {
    return (List<Object[]>) rows;
  }

  private static BigDecimal decimal(Object value) {
    if (value == null) {
      return null;
    }
    return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
  }
}
