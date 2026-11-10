package com.acme.ecommerce.product.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Inbound product creation request with validation and catalog enrichment fields.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateProductRequest {
  @NotBlank @Size(max = 64) String sku;
  @NotBlank @Size(max = 200) String name;
  @NotNull @DecimalMin("0.00") BigDecimal priceAmount;
  @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String priceCurrency;
  boolean active;
  @Min(0) Integer initialQuantity;
  UUID categoryId;
  @Size(max = 200) String sellerName;
  Map<String, Object> specifications;
  @Valid List<ProductImageRequest> images;

  /**
   * Custom validation: active products require initialQuantity >= 1.
   */
  @AssertTrue(message = "initialQuantity must be >= 1 when active=true")
  public boolean isInitialStockValidWhenActive() {
    return !active || (initialQuantity != null && initialQuantity >= 1);
  }
}
