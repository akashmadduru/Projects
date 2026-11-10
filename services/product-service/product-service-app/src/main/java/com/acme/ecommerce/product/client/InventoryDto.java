package com.acme.ecommerce.product.client;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Minimal inventory DTO for inter-service reads (product-service reading from inventory-service).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryDto {
  private UUID productId;
  private int quantity;
  private int reservedQuantity;
}
