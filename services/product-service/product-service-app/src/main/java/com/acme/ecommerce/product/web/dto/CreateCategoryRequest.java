package com.acme.ecommerce.product.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
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

  /**
   * JSON name is {@code active}, the same as {@code CategoryResponse} and the update request
   * (TD-ADM-09). {@code isActive} is still accepted from older clients.
   */
  @JsonProperty("active")
  @JsonAlias("isActive")
  public void setIsActive(Boolean isActive) {
    this.isActive = isActive;
  }

  @JsonProperty("active")
  public Boolean getIsActive() {
    return isActive;
  }
}
