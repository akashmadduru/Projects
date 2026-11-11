package com.acme.ecommerce.inventory.event;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** An inventory lifecycle event published to inventory.events Kafka topic. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryEvent {
  private UUID eventId;
  private InventoryEventType eventType;
  private UUID productId;
  private Instant publishedAt;
  private Integer quantity;
  private Integer reservedQuantity;
  private String status;
}
