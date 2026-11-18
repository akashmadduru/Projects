package com.acme.ecommerce.product.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to update a product category with optimistic-concurrency control. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateCategoryRequest {
  @NotBlank(message = "name is required")
  String name;

  String description;
  boolean isActive;

  @NotNull(message = "version is required for optimistic lock")
  Long version;

  /** JSON name is {@code active}; {@code isActive} is still accepted (TD-ADM-09). */
  @JsonProperty("active")
  @JsonAlias("isActive")
  public void setActive(boolean active) {
    this.isActive = active;
  }

  @JsonProperty("active")
  public boolean isActive() {
    return isActive;
  }
}
