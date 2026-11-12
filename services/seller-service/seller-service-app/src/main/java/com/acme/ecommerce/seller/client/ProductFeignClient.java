package com.acme.ecommerce.seller.client;

import java.util.Map;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** product-service: public list (seller product count), product existence, internal view counts. */
@FeignClient(
    name = "product-service",
    contextId = "sellerProductClient",
    url = "${services.product.url:}",
    configuration = InternalTokenFeignConfig.class)
public interface ProductFeignClient {

  @GetMapping("/api/products")
  Map<String, Object> list(
      @RequestParam("sellerId") UUID sellerId, @RequestParam("size") int size);

  @GetMapping("/api/products/{id}")
  Map<String, Object> get(@PathVariable("id") UUID id);

  /** Returns a bare number or an object with a {@code views} member. */
  @GetMapping("/internal/products/views/by-seller/{sellerId}")
  Object views(
      @PathVariable("sellerId") UUID sellerId,
      @RequestParam("from") String from,
      @RequestParam("to") String to);
}
