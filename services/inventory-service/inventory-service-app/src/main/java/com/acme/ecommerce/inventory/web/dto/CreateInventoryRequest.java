package com.acme.ecommerce.inventory.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Inbound-only, unlike {@link com.acme.ecommerce.inventory.api.dto.InventoryResponse}, which is the
 * published outbound contract. Lives in {@code inventory-service-app}, not the {@code -api} module,
 * mirroring product-service's {@code CreateProductRequest}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateInventoryRequest {
  @NotNull UUID productId;

  @NotNull
  @Min(0)
  Integer quantity;

  @Size(max = 32)
  String warehouseCode;

  @Min(0)
  Integer lowStockThreshold;
}
