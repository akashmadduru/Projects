package com.acme.ecommerce.product.search;

/**
 * A validated search request.
 *
 * @param text the trimmed query, 2..200 characters
 * @param filters the EXPLICIT filters the caller passed (extracted ones are added later)
 * @param page zero-based page
 * @param size page size, 1..50
 */
public record SearchQuery(
    String text, SearchMode mode, SearchSort sort, SearchFilters filters, int page, int size) {}
