package com.acme.ecommerce.product.repository;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Optional narrowing for the catalog list. Every component is optional; an absent one does not
 * filter. The boolean switches only ever NARROW: {@code false} means "no restriction", never "only
 * the opposite" (so {@code featured=false} still returns featured products).
 *
 * @param categoryId a category; a root category also matches products in its subcategories
 * @param query case-insensitive substring of the product name or sku
 * @param minPrice inclusive lower bound on {@code price_amount}
 * @param maxPrice inclusive upper bound on {@code price_amount}
 * @param minRating inclusive lower bound on {@code average_rating}; unrated products never match
 * @param minDiscount inclusive lower bound on {@code discount_percent}
 * @param seller case-insensitive substring of the seller name; products with no seller never match
 * @param inStock hide products KNOWN to be out of stock ({@code last_known_quantity = 0}). A
 *     product whose quantity is not yet known is kept: the figure is a denormalized,
 *     non-authoritative snapshot, and a catalog that has not heard from inventory yet should not
 *     look empty.
 * @param onSale only products with a positive {@code discount_percent}
 * @param featured only products flagged {@code is_featured}
 * @param verifiedSeller only products from a verified seller
 */
public record ProductFilter(
    UUID categoryId,
    String query,
    BigDecimal minPrice,
    BigDecimal maxPrice,
    BigDecimal minRating,
    BigDecimal minDiscount,
    String seller,
    boolean inStock,
    boolean onSale,
    boolean featured,
    boolean verifiedSeller) {

  public static ProductFilter none() {
    return new ProductFilter(null, null, null, null, null, null, null, false, false, false, false);
  }
}
