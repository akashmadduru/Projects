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
}
