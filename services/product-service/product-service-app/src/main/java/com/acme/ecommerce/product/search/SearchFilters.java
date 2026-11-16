package com.acme.ecommerce.product.search;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The filters a search applies BEFORE ranking. Every component is optional; the active-only rule is
 * unconditional and lives in {@link ProductFilterSql}.
 *
 * @param categoryId a category; a root category also matches its direct subcategories
 * @param minPrice inclusive lower bound on {@code price_amount}
 * @param maxPrice inclusive upper bound on {@code price_amount}
 * @param minRating inclusive lower bound on {@code average_rating}; unrated products never match
 * @param brands brand names (case-insensitive, OR); empty = no brand filter
 */
public record SearchFilters(
    UUID categoryId,
    BigDecimal minPrice,
    BigDecimal maxPrice,
    BigDecimal minRating,
    List<String> brands) {}
