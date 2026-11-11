package com.acme.ecommerce.product.event;

import com.acme.ecommerce.product.api.dto.ProductResponse;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A product lifecycle event published to product.events Kafka topic. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductEvent {
  private UUID eventId;
  private ProductEventType eventType;
  private UUID productId;
  private Instant publishedAt;
  private ProductResponse payload;
  private Integer initialQuantity;
}
