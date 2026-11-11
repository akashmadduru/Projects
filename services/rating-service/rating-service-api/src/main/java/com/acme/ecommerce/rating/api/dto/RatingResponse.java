package com.acme.ecommerce.rating.api.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A product rating/review as exposed to callers. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RatingResponse {
  private UUID id;
  private UUID productId;
  private UUID userId;
  private String userDisplayName;
  private int ratingValue;
  private String reviewTitle;
  private String reviewText;
  private boolean isVerifiedPurchase;
  private int helpfulCount;
  private int unhelpfulCount;
  private Long version;
  private Instant createdAt;
  private Instant updatedAt;
}
