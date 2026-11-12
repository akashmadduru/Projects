package com.acme.ecommerce.product.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Autocomplete payload for {@code GET /api/products/suggest}. */
public record SuggestResponse(
    String query, List<Item> products, List<Cat> categories, String didYouMean) {

  /** One product suggestion. */
  public record Item(
      UUID id,
      String slug,
      String name,
      String thumbnailUrl,
      BigDecimal priceAmount,
      BigDecimal salePrice,
      String priceCurrency) {}

  /** One category suggestion. */
  public record Cat(UUID id, String name) {}
}
