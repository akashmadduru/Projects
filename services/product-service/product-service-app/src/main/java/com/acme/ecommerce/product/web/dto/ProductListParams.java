package com.acme.ecommerce.product.web.dto;

import com.acme.ecommerce.product.repository.ProductFilter;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The filter query parameters of {@code GET /api/products}. Paging and sorting are bound separately
 * as a {@code Pageable}.
 *
 * <p>Every parameter is optional and they combine with AND. Range bounds are inclusive. The boolean
 * switches only ever narrow: {@code false} (or absent) means "no restriction".
 *
 * @param categoryId a category; a root category also matches its subcategories' products
 * @param q case-insensitive substring of the name or sku
 * @param minPrice lower price bound
 * @param maxPrice upper price bound
 * @param minRating lowest average rating (0-5); unrated products are excluded
 * @param minDiscount lowest discount percentage (0-100)
 * @param seller case-insensitive substring of the seller name
 * @param inStock {@code true} hides products known to be out of stock (unknown stock is kept)
 * @param onSale {@code true} keeps only discounted products
 * @param featured {@code true} keeps only featured ("top pick") products
 * @param verifiedSeller {@code true} keeps only products from verified sellers
 * @param brand brand names (OR). Bound here only for the record's shape: the controller reads the
 *     raw repeated values with {@link #literalValues}, because Spring would join them with commas
 */
public record ProductListParams(
    UUID categoryId,
    String q,
    BigDecimal minPrice,
    BigDecimal maxPrice,
    BigDecimal minRating,
    BigDecimal minDiscount,
    String seller,
    Boolean inStock,
    Boolean onSale,
    Boolean featured,
    Boolean verifiedSeller,
    String brand,
    UUID sellerId) {

  public static java.util.List<String> splitCsv(String value) {
    if (value == null || value.isBlank()) {
      return java.util.List.of();
    }
    return java.util.Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
  }

  /**
   * Repeated query-parameter values, trimmed, blanks dropped, commas kept ({@code brand=A&brand=B}
   * is two brands; {@code brand=A,B} is ONE brand named "A,B").
   */
  public static java.util.List<String> literalValues(String[] values) {
    if (values == null) {
      return java.util.List.of();
    }
    return java.util.Arrays.stream(values)
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .distinct()
        .toList();
  }

  public ProductFilter toFilter() {
    return new ProductFilter(
        categoryId,
        q,
        minPrice,
        maxPrice,
        minRating,
        minDiscount,
        seller,
        Boolean.TRUE.equals(inStock),
        Boolean.TRUE.equals(onSale),
        Boolean.TRUE.equals(featured),
        Boolean.TRUE.equals(verifiedSeller),
        literalValues(brand == null ? null : new String[] {brand}),
        java.util.Map.of(),
        sellerId);
  }
}
