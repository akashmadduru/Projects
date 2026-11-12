package com.acme.ecommerce.rating.api.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A public rating/review. Deliberately carries NO author user id: the reviewer is identified to the
 * public only by {@code userDisplayName}.
 *
 * <p>{@code isVerifiedPurchase} is a boxed {@code Boolean} so Lombok's getter is {@code
 * getIsVerifiedPurchase()} and the JSON property is literally {@code isVerifiedPurchase} (a
 * primitive would serialise as {@code verifiedPurchase}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RatingResponse {
  private UUID id;
  private UUID productId;
  private String userDisplayName;
  private int ratingValue;
  private String reviewTitle;
  private String reviewText;
  private Boolean isVerifiedPurchase;
  private int helpfulCount;
  private int unhelpfulCount;
  private Long version;
  private Instant createdAt;
  private Instant updatedAt;

  /** Review photos, oldest first; empty when none. */
  @Builder.Default private List<RatingMediaResponse> media = new ArrayList<>();
}
