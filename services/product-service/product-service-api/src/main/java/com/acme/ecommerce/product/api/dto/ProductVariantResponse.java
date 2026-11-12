package com.acme.ecommerce.product.api.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One purchasable variant of a product (size/colour/storage...). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductVariantResponse {
  private UUID id;
  private String sku;
  private Map<String, Object> attributes;

  /** Null means "use the product price". */
  private BigDecimal priceAmount;

  private BigDecimal salePrice;
  private String imageUrl;
  private boolean active;
  private int sortOrder;

  /** Null when inventory could not be reached or does not track this variant. */
  private Integer availableQuantity;
}
