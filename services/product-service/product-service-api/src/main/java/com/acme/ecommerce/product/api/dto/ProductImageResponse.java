package com.acme.ecommerce.product.api.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One image on a {@link ProductResponse}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductImageResponse {
  private UUID id;
  private String url;
  private int displayOrder;
}
