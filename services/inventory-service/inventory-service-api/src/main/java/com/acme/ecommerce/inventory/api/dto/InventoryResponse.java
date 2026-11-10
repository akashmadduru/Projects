package com.acme.ecommerce.inventory.api.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stock-level inventory as exposed to callers.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryResponse {
  private UUID id;
  private UUID productId;
  private int quantity;
  private int reservedQuantity;
  private int availableQuantity;
  private String status;
  private int lowStockThreshold;
  private String warehouseCode;
  private String productName;
  private String productSku;
  private Long version;
  private Instant lastUpdatedAt;
  private Instant createdAt;
  private Instant updatedAt;
}
