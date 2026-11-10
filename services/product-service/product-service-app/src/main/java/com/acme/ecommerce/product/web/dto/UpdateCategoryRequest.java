package com.acme.ecommerce.product.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to update a product category with optimistic-concurrency control.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateCategoryRequest {
  @NotBlank(message = "name is required") String name;
  String description;
  boolean isActive;
  @NotNull(message = "version is required for optimistic lock") Long version;
}
