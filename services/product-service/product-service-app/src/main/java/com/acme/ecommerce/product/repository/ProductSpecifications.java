package com.acme.ecommerce.product.repository;

import com.acme.ecommerce.product.domain.Category;
import com.acme.ecommerce.product.domain.Product;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Translates a {@link ProductFilter} into a single-table query.
 *
 * <p>Still ONE {@code SELECT} against {@code product.products} per page (plus the count), keeping
 * {@link Product}'s "no associations, no N+1" invariant: the subcategory expansion is a correlated
 * subquery on {@code product.categories}, not a join.
 */
public final class ProductSpecifications {

  private static final char LIKE_ESCAPE = '\\';

  private ProductSpecifications() {}

  public static Specification<Product> matching(ProductFilter filter) {
    return matching(filter, null);
  }

  /**
   * @param searchIds when non-null, the free-text query is satisfied by id membership (ids computed
   *     by the full-text + trigram search) instead of the legacy substring match
   */
  public static Specification<Product> matching(ProductFilter filter, Collection<UUID> searchIds) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();

      if (filter.categoryId() != null) {
        // Categories are at most one level deep (CategoryService.MAX_DEPTH), so "this category
        // or one of its direct children" covers every product under it.
        Subquery<UUID> children = query.subquery(UUID.class);
        var category = children.from(Category.class);
        children
            .select(category.get("id"))
            .where(cb.equal(category.get("parentCategoryId"), filter.categoryId()));
        predicates.add(
            cb.or(
                cb.equal(root.get("categoryId"), filter.categoryId()),
                root.get("categoryId").in(children)));
      }

      if (searchIds != null && filter.query() != null && !filter.query().isBlank()) {
        predicates.add(
            searchIds.isEmpty() ? cb.disjunction() : root.get("id").in(searchIds));
      } else if (filter.query() != null && !filter.query().isBlank()) {
        String pattern = "%" + escapeLike(filter.query().trim().toLowerCase(Locale.ROOT)) + "%";
        predicates.add(
            cb.or(
                cb.like(cb.lower(root.get("name")), pattern, LIKE_ESCAPE),
                cb.like(cb.lower(root.get("sku")), pattern, LIKE_ESCAPE)));
      }

      if (filter.minPrice() != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("priceAmount"), filter.minPrice()));
      }
      if (filter.maxPrice() != null) {
        predicates.add(cb.lessThanOrEqualTo(root.get("priceAmount"), filter.maxPrice()));
      }
      if (filter.minRating() != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("averageRating"), filter.minRating()));
      }
      if (filter.minDiscount() != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("discountPercent"), filter.minDiscount()));
      }

      if (filter.seller() != null && !filter.seller().isBlank()) {
        String pattern = "%" + escapeLike(filter.seller().trim().toLowerCase(Locale.ROOT)) + "%";
        predicates.add(cb.like(cb.lower(root.get("sellerName")), pattern, LIKE_ESCAPE));
      }
      if (filter.sellerId() != null) {
        Subquery<UUID> offered = query.subquery(UUID.class);
        var offer = offered.from(com.acme.ecommerce.product.domain.Offer.class);
        offered
            .select(offer.get("id"))
            .where(
                cb.equal(offer.get("productId"), root.get("id")),
                cb.equal(offer.get("sellerId"), filter.sellerId()),
                cb.isTrue(offer.get("active")));
        predicates.add(cb.exists(offered));
      }
      if (filter.inStock()) {
        // Unknown quantity (null) is kept - see ProductFilter#inStock.
        predicates.add(
            cb.or(
                cb.isNull(root.get("lastKnownQuantity")),
                cb.greaterThan(root.<Integer>get("lastKnownQuantity"), 0)));
      }
      if (filter.onSale()) {
        predicates.add(cb.greaterThan(root.<BigDecimal>get("discountPercent"), BigDecimal.ZERO));
      }
      if (filter.featured()) {
        predicates.add(cb.isTrue(root.get("isFeatured")));
      }
      if (filter.verifiedSeller()) {
        predicates.add(cb.isTrue(root.get("isSellerVerified")));
      }

      if (filter.brands() != null && !filter.brands().isEmpty()) {
        predicates.add(
            cb.lower(root.<String>get("brand"))
                .in(filter.brands().stream().map(b -> b.toLowerCase(Locale.ROOT)).toList()));
      }
      if (filter.attributes() != null) {
        for (var entry : filter.attributes().entrySet()) {
          if (entry.getValue().isEmpty()) {
            continue;
          }
          predicates.add(
              cb.function(
                      "jsonb_extract_path_text",
                      String.class,
                      root.get("attributes"),
                      cb.literal(entry.getKey()))
                  .in(entry.getValue()));
        }
      }

      return cb.and(predicates.toArray(Predicate[]::new));
    };
  }

  // Without this, a search for "100%" or "a_b" would be read as LIKE wildcards.
  private static String escapeLike(String value) {
    return value
        .replace(String.valueOf(LIKE_ESCAPE), "" + LIKE_ESCAPE + LIKE_ESCAPE)
        .replace("%", LIKE_ESCAPE + "%")
        .replace("_", LIKE_ESCAPE + "_");
  }
}
