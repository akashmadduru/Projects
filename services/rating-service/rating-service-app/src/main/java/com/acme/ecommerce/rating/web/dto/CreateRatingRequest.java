package com.acme.ecommerce.rating.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to create a product rating. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateRatingRequest {
  @NotNull(message = "ratingValue is required")
  @Min(value = 1, message = "ratingValue must be at least 1")
  @Max(value = 5, message = "ratingValue must be at most 5")
  Integer ratingValue;

  String reviewTitle;
  String reviewText;
  Boolean isVerifiedPurchase;
}
