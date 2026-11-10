package com.acme.ecommerce.product.web.dto;

import com.acme.ecommerce.inventory.api.dto.InventoryResponse;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The composed response for GET /api/products/catalog/products/{id}.
 * App-internal aggregate combining product and inventory data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductCatalogAggregateResponse {
  private ProductResponse product;
  private InventoryResponse inventory;
}
