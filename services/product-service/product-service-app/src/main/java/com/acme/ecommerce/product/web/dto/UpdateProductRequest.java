package com.acme.ecommerce.product.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
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

/** Product update request with optimistic-concurrency control and catalog enrichment fields. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateProductRequest {
  @NotBlank
  @Size(max = 200)
  String name;

  @NotNull
  @DecimalMin("0.00")
  BigDecimal priceAmount;

  @NotBlank
  @Pattern(regexp = "^[A-Z]{3}$")
  String priceCurrency;

  boolean active;
  @NotNull Long version;
  UUID categoryId;

  @Size(max = 200)
  String sellerName;

  Map<String, Object> specifications;
  @Valid List<ProductImageRequest> images;
}
