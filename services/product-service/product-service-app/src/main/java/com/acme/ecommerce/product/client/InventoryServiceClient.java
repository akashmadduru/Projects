package com.acme.ecommerce.product.client;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Feign client for inventory-service READ-ONLY operations.
 *
 * <p>Product-service uses this to fetch stock levels for denormalization. Circuit breaker fallback
 * returns null on outage.
 *
 * <p>NO WRITES THROUGH THIS CLIENT - product-service publishes events, not direct calls, when it
 * needs inventory updates.
 */
@FeignClient(
    name = "inventory-service",
    contextId = "inventoryServiceClient",
    url = "${services.inventory-service.url:http://inventory-service:8083}",
    fallback = InventoryServiceClientFallback.class)
public interface InventoryServiceClient {

  /**
   * Fetches inventory for a product.
   *
   * @param productId the product identifier
   * @return the inventory, or null if not found or service is down
   */
  @GetMapping("/api/inventory/by-product/{productId}")
  InventoryDto getByProduct(@PathVariable UUID productId);
}
