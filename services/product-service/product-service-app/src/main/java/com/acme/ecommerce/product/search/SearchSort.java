package com.acme.ecommerce.product.search;

import com.acme.ecommerce.common.core.error.ValidationException;
import java.util.Locale;

/**
 * Ordering of a search result. Each value carries its own SQL ORDER BY (a closed set, no input).
 */
public enum SearchSort {
  RELEVANCE("f.score desc, p.average_rating desc nulls last, f.id"),
  PRICE_ASC("p.price_amount asc, f.score desc, f.id"),
  PRICE_DESC("p.price_amount desc, f.score desc, f.id"),
  NEWEST("p.created_at desc, f.score desc, f.id");

  private final String orderBy;

  SearchSort(String orderBy) {
    this.orderBy = orderBy;
  }

  /** The ORDER BY expression over the {@code fused f} / {@code products p} join. */
  public String orderBy() {
    return orderBy;
  }

  /** Case-insensitive parse; a blank value means {@link #RELEVANCE}. */
  public static SearchSort parse(String value) {
    if (value == null || value.isBlank()) {
      return RELEVANCE;
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ValidationException.forField(
          "sort", "must be one of relevance, price_asc, price_desc, newest");
    }
  }
}
