package com.acme.ecommerce.product.event;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The message consumed from Kafka topic inventory.events. Consumer-side duplicate for loose
 * coupling between services.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryEvent {
  private UUID eventId;
  private InventoryEventType eventType;
  private UUID productId;
  private Instant occurredAt;
  private Integer quantity;
  private Integer requestedQuantity;
  private String reason;
}
