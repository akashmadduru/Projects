package com.acme.ecommerce.inventory.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The subset of product-service's ProductResponse this consumer actually needs.
 * Deliberately narrow for forward compatibility with producer schema evolution.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductSnapshot {
  private String name;
  private String sku;
}
