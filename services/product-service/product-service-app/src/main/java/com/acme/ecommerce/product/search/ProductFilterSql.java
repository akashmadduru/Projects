package com.acme.ecommerce.product.search;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the ONE predicate fragment that restricts every retrieval leg and the facets of a search.
 *
 * <p>Because the keyword leg, the vector leg and the facet query all splice in the same fragment
 * (alias {@code p} = {@code product.products}), the facet counts can never disagree with the list.
 * Only column names and operators are concatenated; every value travels as a bound parameter.
 */
final class ProductFilterSql {

  /** The predicate and the parameters it references. */
  record Fragment(String sql, Map<String, Object> params) {}

  private ProductFilterSql() {}

  static Fragment of(SearchFilters filters) {
    StringBuilder sql = new StringBuilder("p.active = true");
    Map<String, Object> params = new LinkedHashMap<>();
    if (filters.categoryId() != null) {
      // Categories are one level deep: "this category or one of its direct children".
      sql.append(
          " and (p.category_id = :categoryId or p.category_id in "
              + "(select c.id from product.categories c where c.parent_category_id = :categoryId))");
      params.put("categoryId", filters.categoryId());
    }
    if (filters.minPrice() != null) {
      sql.append(" and p.price_amount >= :minPrice");
      params.put("minPrice", filters.minPrice());
    }
    if (filters.maxPrice() != null) {
      sql.append(" and p.price_amount <= :maxPrice");
      params.put("maxPrice", filters.maxPrice());
    }
    if (filters.minRating() != null) {
      sql.append(" and p.average_rating >= :minRating");
      params.put("minRating", filters.minRating());
    }
    if (filters.brands() != null && !filters.brands().isEmpty()) {
      sql.append(" and lower(p.brand) in (:brands)");
      params.put("brands", filters.brands().stream().map(b -> b.toLowerCase(Locale.ROOT)).toList());
    }
    return new Fragment(sql.toString(), params);
  }
}
