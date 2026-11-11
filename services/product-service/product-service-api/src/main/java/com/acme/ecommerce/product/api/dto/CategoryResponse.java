package com.acme.ecommerce.product.api.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A product category as exposed to callers. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryResponse {
  private UUID id;
  private String name;
  private String description;
  private UUID parentCategoryId;
  private int depthLevel;
  private boolean isActive;
  private Long version;
}
