package com.acme.ecommerce.product.search;

import com.acme.ecommerce.product.api.dto.ProductResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /api/products/search}. The first two members ({@code content}, {@code page}) are the
 * SAME envelope {@code GET /api/products} returns ({@code PagedModel}), so a client's existing page
 * handling works unchanged; the rest is what only search knows.
 *
 * @param effectiveMode the mode actually used (lower-case); differs from the requested mode only
 *     when {@code degraded}
 * @param degraded the caller asked for semantic or hybrid ranking but got keyword ranking because
 *     the embedding provider is disabled or unavailable
 * @param interpretedFilters filters inferred from the query text
 * @param matchedBy per product on this page: {@code keyword}, {@code semantic} or {@code both}
 */
public record SearchResponse(
    List<ProductResponse> content,
    PageInfo page,
    String effectiveMode,
    boolean degraded,
    InterpretedFilters interpretedFilters,
    Map<UUID, String> matchedBy) {

  /**
   * Same member names as Spring Data's {@code PagedModel.PageMetadata}. {@code totalElements} is
   * capped at twice the per-leg candidate limit by design (see {@code topN}).
   */
  public record PageInfo(int size, int number, long totalElements, int totalPages) {}
}
