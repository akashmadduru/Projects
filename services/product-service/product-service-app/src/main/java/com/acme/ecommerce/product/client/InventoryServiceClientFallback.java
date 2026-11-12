package com.acme.ecommerce.product.client;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Circuit breaker fallback for InventoryServiceClient.
 *
 * <p>Returns null when inventory-service is unreachable, allowing product operations to proceed
 * without denormalization (graceful degradation).
 */
@Slf4j
@Component
public class InventoryServiceClientFallback implements InventoryServiceClient {

  @Override
  public InventoryDto getByProduct(UUID productId) {
    log.warn("InventoryServiceClient fallback for getByProduct({}): returning null", productId);
    return null;
  }

  @Override
  public java.util.List<InventoryDto.VariantStock> getVariantStock(
      java.util.List<UUID> ids, String internalToken) {
    return java.util.List.of();
  }

  @Override
  public java.util.List<InventoryDto.StockRow> stockRows(UUID productId, String internalToken) {
    throw new IllegalStateException("inventory-service unavailable");
  }

  @Override
  public void putStock(InventoryDto.StockWrite body, String internalToken) {
    throw new IllegalStateException("inventory-service unavailable");
  }

  @Override
  public InventoryDto getByProductWithAuth(UUID productId, String authorization) {
    return null;
  }
}
