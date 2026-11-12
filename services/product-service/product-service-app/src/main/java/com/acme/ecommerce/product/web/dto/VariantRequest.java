package com.acme.ecommerce.product.web.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One entry of {@code PUT /api/products/{id}/variants} (full replace). */
@Data
@NoArgsConstructor
public class VariantRequest {
  UUID id;
  String sku;
  Map<String, Object> attributes;
  BigDecimal priceAmount;
  BigDecimal salePrice;
  String imageUrl;
  Boolean active;
  Integer sortOrder;
}
