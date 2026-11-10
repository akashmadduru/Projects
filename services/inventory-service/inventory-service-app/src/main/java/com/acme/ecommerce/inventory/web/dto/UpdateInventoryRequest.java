package com.acme.ecommerce.inventory.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Update inventory request with optimistic-concurrency control.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateInventoryRequest {
  @NotNull @Min(0) Integer quantity;
  @NotNull Long version;
  @Size(max = 32) String warehouseCode;
  @Min(0) Integer lowStockThreshold;
}
