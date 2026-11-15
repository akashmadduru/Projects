package com.acme.ecommerce.product.search;

import com.acme.ecommerce.common.core.error.ValidationException;
import java.util.Locale;

/** Which retrieval legs a search may use. */
public enum SearchMode {
  KEYWORD,
  SEMANTIC,
  HYBRID;

  /** Case-insensitive parse; a blank value means {@link #HYBRID}. */
  public static SearchMode parse(String value) {
    if (value == null || value.isBlank()) {
      return HYBRID;
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw ValidationException.forField("mode", "must be one of keyword, semantic, hybrid");
    }
  }

  /** Lower-case wire name. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}
