package com.acme.ecommerce.rating.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of POST /api/ratings/products/{productId}. Verified-purchase status and the display name are
 * never accepted from the client - they are derived server-side.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateRatingRequest {
  @NotNull(message = "ratingValue is required")
  @Min(value = 1, message = "ratingValue must be at least 1")
  @Max(value = 5, message = "ratingValue must be at most 5")
  Integer ratingValue;

  @Size(max = 200, message = "reviewTitle must be at most 200 characters")
  String reviewTitle;

  @Size(max = 5000, message = "reviewText must be at most 5000 characters")
  String reviewText;
}
