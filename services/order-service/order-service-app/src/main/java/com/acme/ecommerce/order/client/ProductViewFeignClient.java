package com.acme.ecommerce.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * product-service read with the Phase 7 {@code variants} array, mapped to a local view so this
 * service does not depend on the shared ProductResponse gaining the field.
 */
@FeignClient(
    name = "product-service",
    contextId = "productViewClient",
    url = "${services.product.url:}")
public interface ProductViewFeignClient {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Variant(
      UUID id,
      String sku,
      Map<String, String> attributes,
      BigDecimal priceAmount,
      BigDecimal salePrice,
      String imageUrl,
      Boolean active) {}

  /** One seller offer (marketplace); absent with a product-service that has no offers yet. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Offer(
      UUID offerId,
      UUID sellerId,
      String sellerName,
      UUID variantId,
      BigDecimal price,
      BigDecimal salePrice,
      String currency,
      Integer dispatchDays,
      Integer availableQuantity) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ProductView(
      UUID id,
      String sku,
      String name,
      BigDecimal priceAmount,
      String priceCurrency,
      boolean active,
      BigDecimal salePrice,
      String thumbnailUrl,
      List<Variant> variants,
      List<Offer> offers,
      Offer buyBox) {}

  @GetMapping("/api/products/{id}")
  ProductView findById(@PathVariable("id") UUID id);
}
