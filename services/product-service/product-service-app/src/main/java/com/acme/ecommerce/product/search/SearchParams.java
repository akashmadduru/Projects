package com.acme.ecommerce.product.search;

import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.product.web.ProductController;
import com.acme.ecommerce.product.web.dto.ProductListParams;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The query parameters of {@code GET /api/products/search} and {@code /search/facets}, and their
 * validation. Bound with {@code @ModelAttribute} exactly like {@link ProductListParams}.
 *
 * @param q the search text, 2..{@value ProductController#MAX_TEXT_FILTER} characters
 * @param mode {@code keyword}, {@code semantic} or {@code hybrid} (default)
 * @param sort {@code relevance} (default), {@code price_asc}, {@code price_desc} or {@code newest}
 * @param brand brand names (OR); see {@link #toQuery(java.util.List)}
 */
public record SearchParams(
    String q,
    String mode,
    String sort,
    UUID categoryId,
    BigDecimal minPrice,
    BigDecimal maxPrice,
    String brand,
    BigDecimal minRating,
    Integer page,
    Integer size) {

  /** Smallest query worth searching: one character matches half the catalog and costs a call. */
  public static final int MIN_QUERY_LENGTH = 2;

  public static final int DEFAULT_SIZE = 20;
  public static final int MAX_SIZE = 50;

  /** Validates and converts; every failure is a {@code VALIDATION_FAILED} problem+json (400). */
  public SearchQuery toQuery() {
    return toQuery(ProductListParams.literalValues(brand == null ? null : new String[] {brand}));
  }

  /**
   * @param brands the raw repeated {@code brand} values (Spring would join them with commas into
   *     {@link #brand}, so the controller passes them separately)
   */
  public SearchQuery toQuery(java.util.List<String> brands) {
    String text = q == null ? "" : q.strip();
    if (text.length() < MIN_QUERY_LENGTH) {
      throw ValidationException.forField(
          "q", "must be at least " + MIN_QUERY_LENGTH + " characters");
    }
    if (text.length() > ProductController.MAX_TEXT_FILTER) {
      throw ValidationException.forField(
          "q", "must be at most " + ProductController.MAX_TEXT_FILTER + " characters");
    }
    requireAtLeastZero("minPrice", minPrice);
    requireAtLeastZero("maxPrice", maxPrice);
    if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
      throw ValidationException.forField("minPrice", "must not be greater than maxPrice");
    }
    if (minRating != null
        && (minRating.signum() < 0 || minRating.compareTo(BigDecimal.valueOf(5)) > 0)) {
      throw ValidationException.forField("minRating", "must be between 0 and 5");
    }
    int pageNumber = page == null ? 0 : page;
    if (pageNumber < 0) {
      throw ValidationException.forField("page", "must be at least 0");
    }
    int pageSize = size == null ? DEFAULT_SIZE : size;
    if (pageSize < 1 || pageSize > MAX_SIZE) {
      throw ValidationException.forField("size", "must be between 1 and " + MAX_SIZE);
    }
    SearchFilters filters = new SearchFilters(categoryId, minPrice, maxPrice, minRating, brands);
    return new SearchQuery(
        text, SearchMode.parse(mode), SearchSort.parse(sort), filters, pageNumber, pageSize);
  }

  private static void requireAtLeastZero(String field, BigDecimal value) {
    if (value != null && value.signum() < 0) {
      throw ValidationException.forField(field, "must be at least 0");
    }
  }
}
