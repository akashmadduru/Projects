package com.acme.ecommerce.product.client;

import java.util.List;
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

  /** Per-variant stock, when inventory-service tracks variants; may be null. */
  private List<VariantStock> variants;

  /** Stock of one variant. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class VariantStock {
    private UUID variantId;
    private int quantity;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public static class StockRow {
    private UUID productId;
    private UUID variantId;
    private UUID sellerId;
    private UUID offerId;
    private String warehouseCode;
    private int quantity;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class StockWrite {
    private UUID productId;
    private UUID variantId;
    private UUID sellerId;
    private UUID offerId;
    private String warehouseCode;
    private int quantity;
  }
}
