package com.acme.ecommerce.product.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to create a product category. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateCategoryRequest {
  @NotBlank(message = "name is required")
  String name;

  String description;
  UUID parentCategoryId;
  Boolean isActive;
}
