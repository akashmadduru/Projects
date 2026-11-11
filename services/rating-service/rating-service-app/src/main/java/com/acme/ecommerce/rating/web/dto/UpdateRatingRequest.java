package com.acme.ecommerce.rating.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to update a product rating with optimistic-concurrency control. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateRatingRequest {
  @Min(value = 1, message = "ratingValue must be at least 1")
  @Max(value = 5, message = "ratingValue must be at most 5")
  Integer ratingValue;

  String reviewTitle;
  String reviewText;

  @NotNull(message = "version is required for optimistic lock")
  Long version;
}
