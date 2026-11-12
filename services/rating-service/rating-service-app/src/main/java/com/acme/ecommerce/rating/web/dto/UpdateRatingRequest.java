package com.acme.ecommerce.rating.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body of PUT /api/ratings/{id}. {@code version} is the optimistic-lock token from the read. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateRatingRequest {
  @Min(value = 1, message = "ratingValue must be at least 1")
  @Max(value = 5, message = "ratingValue must be at most 5")
  Integer ratingValue;

  @Size(max = 200, message = "reviewTitle must be at most 200 characters")
  String reviewTitle;

  @Size(max = 5000, message = "reviewText must be at most 5000 characters")
  String reviewText;

  @NotNull(message = "version is required for optimistic lock")
  Long version;
}
