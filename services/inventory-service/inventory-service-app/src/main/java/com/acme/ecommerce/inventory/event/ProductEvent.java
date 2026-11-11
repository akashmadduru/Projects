package com.acme.ecommerce.inventory.event;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The message consumed from Kafka topic product.events. Consumer-side duplicate for loose coupling
 * between services.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductEvent {
  private UUID eventId;
  private ProductEventType eventType;
  private UUID productId;
  private Instant occurredAt;
  private ProductSnapshot payload;
  private Integer initialQuantity;
}
