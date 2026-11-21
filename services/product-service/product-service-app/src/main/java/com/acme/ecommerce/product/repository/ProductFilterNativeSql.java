package com.acme.ecommerce.product.repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The SQL twin of {@link ProductSpecifications}: the same {@link ProductFilter} as one native
 * predicate (alias {@code p} = {@code product.products}), for the facet aggregates that the
 * Criteria API cannot express ({@code jsonb_each_text}, grouping on {@code lower(...)}).
 *
 * <p>Facets drop their OWN filter: the brand counts ignore the selected brands, an attribute's
 * counts ignore that attribute's selection, and the price range ignores the price bounds, so
 * picking one brand still shows how many products every other brand would add. {@link Omit} says
 * which part to leave out.
 *
 * <p>KEEP IN STEP WITH {@link ProductSpecifications}. {@code ProductFilterNativeSqlTest} fails when
 * a {@link ProductFilter} component is added without a clause here. Only column names and operators
 * are concatenated; every value travels as a bound parameter.
 */
public final class ProductFilterNativeSql {

  /** The predicate and the parameters it references. */
  public record Fragment(String sql, Map<String, Object> params) {}

  /** Which part of the filter a facet leaves out. */
  public record Omit(boolean brands, boolean price, String attributeKey) {
    public static final Omit NONE = new Omit(false, false, null);
    public static final Omit BRANDS = new Omit(true, false, null);
    public static final Omit PRICE = new Omit(false, true, null);

    public static Omit attribute(String key) {
      return new Omit(false, false, key);
    }
  }

  private ProductFilterNativeSql() {}

  /** A fragment restricting to an explicit id set (the candidates of a hybrid search). */
  public static Fragment ofIds(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return new Fragment("false", Map.of());
    }
    return new Fragment("p.id in (:ids)", Map.of("ids", List.copyOf(ids)));
  }

  /**
   * @param searchIds when non-null, the free-text query is satisfied by id membership, exactly as
   *     {@link ProductSpecifications#matching(ProductFilter, Collection)} does
   */
  public static Fragment of(ProductFilter filter, Collection<UUID> searchIds, Omit omit) {
    StringBuilder sql = new StringBuilder("true");
    Map<String, Object> params = new LinkedHashMap<>();

    if (filter.categoryId() != null) {
      sql.append(
          " and (p.category_id = :categoryId or p.category_id in "
              + "(select c.id from product.categories c where c.parent_category_id = :categoryId))");
      params.put("categoryId", filter.categoryId());
    }
    if (filter.query() != null && !filter.query().isBlank()) {
      if (searchIds != null) {
        if (searchIds.isEmpty()) {
          sql.append(" and false");
        } else {
          sql.append(" and p.id in (:searchIds)");
          params.put("searchIds", List.copyOf(searchIds));
        }
      } else {
        sql.append(
            " and (lower(p.name) like :queryPattern escape '\\'"
                + " or lower(p.sku) like :queryPattern escape '\\')");
        params.put("queryPattern", likePattern(filter.query()));
      }
    }
    if (!omit.price()) {
      if (filter.minPrice() != null) {
        sql.append(" and p.price_amount >= :minPrice");
        params.put("minPrice", filter.minPrice());
      }
      if (filter.maxPrice() != null) {
        sql.append(" and p.price_amount <= :maxPrice");
        params.put("maxPrice", filter.maxPrice());
      }
    }
    if (filter.minRating() != null) {
      sql.append(" and p.average_rating >= :minRating");
      params.put("minRating", filter.minRating());
    }
    if (filter.minDiscount() != null) {
      sql.append(" and p.discount_percent >= :minDiscount");
      params.put("minDiscount", filter.minDiscount());
    }
    if (filter.seller() != null && !filter.seller().isBlank()) {
      sql.append(" and lower(p.seller_name) like :sellerPattern escape '\\'");
      params.put("sellerPattern", likePattern(filter.seller()));
    }
    if (filter.sellerId() != null) {
      sql.append(
          " and exists (select 1 from product.offers o where o.product_id = p.id"
              + " and o.seller_id = :sellerId and o.active = true)");
      params.put("sellerId", filter.sellerId());
    }
    if (filter.inStock()) {
      sql.append(" and (p.last_known_quantity is null or p.last_known_quantity > 0)");
    }
    if (filter.onSale()) {
      sql.append(" and p.discount_percent > 0");
    }
    if (filter.featured()) {
      sql.append(" and p.is_featured = true");
    }
    if (filter.verifiedSeller()) {
      sql.append(" and p.is_seller_verified = true");
    }
    if (!omit.brands() && filter.brands() != null && !filter.brands().isEmpty()) {
      sql.append(" and lower(p.brand) in (:brands)");
      params.put("brands", lowered(filter.brands()));
    }
    if (filter.attributes() != null) {
      int i = 0;
      for (var entry : filter.attributes().entrySet()) {
        if (entry.getValue().isEmpty() || entry.getKey().equals(omit.attributeKey())) {
          continue;
        }
        sql.append(" and lower(p.attributes ->> :attrKey")
            .append(i)
            .append(") in (:attrValues")
            .append(i)
            .append(')');
        params.put("attrKey" + i, entry.getKey());
        params.put("attrValues" + i, lowered(entry.getValue()));
        i++;
      }
    }
    return new Fragment(sql.toString(), params);
  }

  private static String likePattern(String raw) {
    return "%" + ProductSpecifications.escapeLike(raw.trim().toLowerCase(Locale.ROOT)) + "%";
  }

  private static List<String> lowered(List<String> values) {
    return values.stream().map(v -> v.toLowerCase(Locale.ROOT)).toList();
  }
}
