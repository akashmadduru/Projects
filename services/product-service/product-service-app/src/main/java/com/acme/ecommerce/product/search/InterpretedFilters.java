package com.acme.ecommerce.product.search;

import java.math.BigDecimal;

/**
 * Filters the server inferred from the query text ("under 3000", "4 stars and above", "from Acme")
 * and applied because the caller had not passed the matching explicit parameter. Returned so the UI
 * can show them as removable chips; any component may be null.
 */
public record InterpretedFilters(
    BigDecimal minPrice, BigDecimal maxPrice, BigDecimal minRating, String brand) {

  public static InterpretedFilters none() {
    return new InterpretedFilters(null, null, null, null);
  }

  public boolean isEmpty() {
    return minPrice == null && maxPrice == null && minRating == null && brand == null;
  }
}
