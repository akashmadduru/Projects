package com.acme.ecommerce.rating.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The caller's relationship to a product's reviews: may they write one, and the one they wrote. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MyRatingStatusResponse {
  /** True when the caller bought the product and has not reviewed it yet. */
  private boolean canReview;

  /** True when the caller has a non-cancelled order containing the product. */
  private boolean purchased;

  /** The caller's own review, or null. */
  private RatingResponse review;
}
