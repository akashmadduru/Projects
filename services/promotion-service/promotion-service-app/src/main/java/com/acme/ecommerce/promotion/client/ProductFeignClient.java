package com.acme.ecommerce.promotion.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** product-service public catalog read (category of a product, for exchange programs). */
@FeignClient(
    name = "product-service",
    contextId = "promotionProductClient",
    url = "${services.product.url:}")
public interface ProductFeignClient {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ProductRef(UUID id, UUID categoryId) {}

  @GetMapping("/api/products/{id}")
  ProductRef product(@PathVariable("id") UUID id);
}
