package com.acme.ecommerce.rating.event;

import com.acme.ecommerce.rating.api.dto.RatingResponse;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A rating lifecycle event published to {@code rating.events} Kafka topic. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RatingEvent {
  private UUID eventId;
  private RatingEventType eventType;
  private UUID ratingId;
  private UUID productId;
  private Instant publishedAt;
  private RatingResponse payload;
}
