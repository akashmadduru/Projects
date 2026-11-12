package com.acme.ecommerce.product.client;

import java.util.UUID;
import org.springframework.web.bind.annotation.RequestHeader;
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

  /** Per-variant stock by variant id (internal endpoint, X-Internal-Token). */
  @GetMapping("/internal/inventory/variants")
  java.util.List<InventoryDto.VariantStock> getVariantStock(
      @org.springframework.web.bind.annotation.RequestParam("ids") java.util.List<UUID> ids,
      @RequestHeader("X-Internal-Token") String internalToken);

  /** Same read, forwarding the caller's bearer token (inventory reads need authentication). */
  /** Stock rows per (variant, seller, warehouse) for one product (internal). */
  @GetMapping("/internal/inventory/stock/by-product/{productId}")
  java.util.List<InventoryDto.StockRow> stockRows(
      @PathVariable UUID productId, @RequestHeader("X-Internal-Token") String internalToken);

  /** Sets stock for a seller offer at a warehouse (internal). */
  @org.springframework.web.bind.annotation.PutMapping("/internal/inventory/stock")
  void putStock(
      @org.springframework.web.bind.annotation.RequestBody InventoryDto.StockWrite body,
      @RequestHeader("X-Internal-Token") String internalToken);

  @GetMapping("/api/inventory/by-product/{productId}")
  InventoryDto getByProductWithAuth(
      @PathVariable UUID productId,
      @RequestHeader(value = "Authorization", required = false) String authorization);
}
