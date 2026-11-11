package com.acme.ecommerce.product.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The subset of rating-service's RatingResponse this consumer actually needs. Deliberately narrow
 * for forward compatibility with producer schema evolution.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class RatingSnapshot {
  private int ratingValue;
  private String reviewTitle;
  private String reviewText;
  private boolean isVerifiedPurchase;
  private int helpfulCount;
  private int unhelpfulCount;
}
