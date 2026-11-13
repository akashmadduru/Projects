package com.acme.ecommerce.product.service;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.inventory.api.dto.InventoryResponse;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import com.acme.ecommerce.product.client.InventoryFeignClient;
import com.acme.ecommerce.product.web.dto.ProductCatalogAggregateResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;

/**
 * Composes a product with its stock level in one response.
 *
 * <p>THE ONLY CLASS IN THIS SERVICE THAT MAKES A SYNCHRONOUS OUTBOUND CALL, and scoped exactly to
 * the narrow, explicit exception docs/architecture/adr/0003 (Decision 2) carved out of the
 * platform's "no synchronous call between product-service and inventory-service" non-goal - a
 * single, read-only, non-mutating {@code GET}. Do not extend this pattern to any other endpoint
 * without an equally explicit approval-gate decision.
 *
 * <p>THE PRODUCT LOOKUP FAILS LOUDLY; THE INVENTORY LOOKUP NEVER DOES. {@link
 * ProductCatalogService#findById(UUID)} throws {@link ResourceNotFoundException} exactly as {@code
 * GET /api/products/{id}} does, and that propagates here unmodified - a missing product is a 404
 * for the whole aggregate. The inventory half is advisory only (HLD S7.3): any failure reaching
 * inventory-service - a timeout, a 5xx, a genuine 404 for a not-yet-provisioned row (the
 * choreographed creation-saga's window, HLD S7.1), or the {@code aggregateCb} breaker being open -
 * degrades to {@code inventory: null} rather than surfacing as a failure of this endpoint. This
 * mirrors the "inventory data is advisory, not authoritative" posture {@code
 * Product.lastKnownQuantity} already established; this class just composes that same advisory data
 * into one response instead of requiring the caller to make two.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogAggregateService {

  /**
   * The circuit breaker's name. A SEPARATE INSTANCE from every other breaker in the platform
   * (gateway's {@code productCb}/{@code inventoryCb}/{@code authCb} included) - this one protects
   * exactly this one outbound call, so a slow-but-healthy inventory-service under this call's load
   * cannot be conflated with, or trip, anything else.
   */
  static final String CIRCUIT_BREAKER_NAME = "aggregateCb";

  private final ProductCatalogService products;
  private final InventoryFeignClient inventoryClient;
  private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

  /**
   * The product plus its (possibly absent) stock level.
   *
   * @param id the product identifier
   * @return the composed response
   * @throws ResourceNotFoundException when no such product exists - never thrown for an inventory
   *     failure, only for the product lookup itself
   */
  public ProductCatalogAggregateResponse getAggregate(UUID id) {
    ProductResponse product = products.findById(id);
    InventoryResponse inventory = findInventoryOrNull(id);
    return new ProductCatalogAggregateResponse(product, inventory);
  }

  /**
   * The outbound call, wrapped in {@code aggregateCb}. Any {@link Throwable} the call or the
   * breaker itself produces - a {@code FeignException.NotFound}, a connect/read timeout, a 5xx
   * translated by Feign's default error decoder, or {@code CallNotPermittedException} when the
   * breaker is open - is caught by the fallback below and turned into {@code null}, never rethrown.
   */
  private InventoryResponse findInventoryOrNull(UUID productId) {
    return circuitBreakerFactory
        .create(CIRCUIT_BREAKER_NAME)
        .run(
            () -> inventoryClient.findByProductId(productId),
            throwable -> {
              log.debug(
                  "Inventory lookup degraded to null for productId={}, cause={}: {}",
                  productId,
                  throwable.getClass().getSimpleName(),
                  throwable.getMessage());
              return null;
            });
  }
}
