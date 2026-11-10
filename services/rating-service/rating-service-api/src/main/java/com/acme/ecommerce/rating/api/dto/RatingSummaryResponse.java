package com.acme.ecommerce.rating.api.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregate rating summary for a product.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RatingSummaryResponse {
  private UUID productId;
  private BigDecimal averageRating;
  private int ratingCount;
  private int reviewCount;
  private Map<Integer, Integer> ratingDistribution;
}
