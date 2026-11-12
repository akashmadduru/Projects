package com.acme.ecommerce.product.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Consumer-side copy of rating-service's {@code RatingSummaryResponse} (this service deliberately
 * has no dependency on {@code rating-service-api}).
 *
 * @param productId the product summarised
 * @param averageRating mean rating, {@code null} or zero when unrated
 * @param ratingCount number of ratings
 * @param reviewCount number of ratings carrying review text
 * @param ratingDistribution count per star value, keys 1..5
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RatingSummary(
    UUID productId,
    BigDecimal averageRating,
    int ratingCount,
    int reviewCount,
    Map<Integer, Integer> ratingDistribution) {}
