package com.acme.ecommerce.rating.api.dto;

import java.util.UUID;

/** One review photo; {@code url} is the public, cacheable stream path. */
public record RatingMediaResponse(UUID id, String url) {}
