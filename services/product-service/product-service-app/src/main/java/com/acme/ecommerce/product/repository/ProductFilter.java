package com.acme.ecommerce.product.repository;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Optional narrowing for the catalog list. Every component is nullable; a null component does not
 * filter.
 *
 * @param categoryId a category; a root category also matches products in its subcategories
 * @param query case-insensitive substring of the product name or sku
 * @param minPrice inclusive lower bound on {@code price_amount}
 * @param maxPrice inclusive upper bound on {@code price_amount}
 * @param minRating inclusive lower bound on {@code average_rating}; unrated products never match
 * @param minDiscount inclusive lower bound on {@code discount_percent}
 */
public record ProductFilter(
    UUID categoryId,
    String query,
    BigDecimal minPrice,
    BigDecimal maxPrice,
    BigDecimal minRating,
    BigDecimal minDiscount) {

  public static ProductFilter none() {
    return new ProductFilter(null, null, null, null, null, null);
  }
}
