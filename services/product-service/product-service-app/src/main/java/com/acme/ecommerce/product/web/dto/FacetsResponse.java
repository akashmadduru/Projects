package com.acme.ecommerce.product.web.dto;

import java.math.BigDecimal;
import java.util.List;

/** Facet counts for {@code GET /api/products/facets}. */
public record FacetsResponse(
    List<Value> brands, List<Attribute> attributes, PriceRange price) {

  /** A facet value and the number of matching products. */
  public record Value(String value, long count) {}

  /** An attribute key with its values. */
  public record Attribute(String key, List<Value> values) {}

  /** Min/max price of the filtered set (null when empty). */
  public record PriceRange(BigDecimal min, BigDecimal max) {}
}
