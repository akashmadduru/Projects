package com.acme.ecommerce.product.event;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.product.service.ProductCatalogService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the listener's dispatch logic - the exhaustive {@code switch} over {@link
 * InventoryEventType} - with no Spring context and no running Kafka broker. Mirrors
 * inventory-service's {@code ProductEventListenerTest} exactly.
 *
 * <p>{@link ProductCatalogService} is mocked: what is under test is which of its methods a given
 * {@code eventType} routes to, not {@link ProductCatalogService}'s own idempotency decisions -
 * those are covered by {@code ProductCatalogServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class InventoryEventListenerTest {

  @Mock private ProductCatalogService catalog;

  @Test
  @DisplayName(
      "INVENTORY_QUANTITY_CHANGED dispatches to applyInventorySignal(productId, newQuantity)")
  void quantityChangedAppliesSignal() {
    InventoryEventListener listener = new InventoryEventListener(catalog);
    UUID productId = UUID.randomUUID();
    InventoryEvent event = event(InventoryEventType.INVENTORY_QUANTITY_CHANGED, productId, 42);

    listener.onInventoryEvent(event);

    verify(catalog).applyInventorySignal(eq(productId), eq(42));
  }

  @Test
  @DisplayName("INVENTORY_LOW_STOCK is log-only - no ProductCatalogService write of any kind")
  void lowStockIsLogOnly() {
    InventoryEventListener listener = new InventoryEventListener(catalog);
    InventoryEvent event = event(InventoryEventType.INVENTORY_LOW_STOCK, UUID.randomUUID(), 3);

    listener.onInventoryEvent(event);

    verifyNoInteractions(catalog);
  }

  @Test
  @DisplayName(
      "INVENTORY_PROVISIONING_FAILED dispatches to the existing delete(productId) path - the"
          + " choreographed creation-saga's compensating delete")
  void provisioningFailedCompensatesWithDelete() {
    InventoryEventListener listener = new InventoryEventListener(catalog);
    UUID productId = UUID.randomUUID();
    InventoryEvent event = failedEvent(productId);

    listener.onInventoryEvent(event);

    verify(catalog).delete(productId);
  }

  @Test
  @DisplayName(
      "INVENTORY_PROVISIONING_FAILED redelivered for an already-compensated (or otherwise"
          + " already-absent) product no-ops rather than throwing - no second PRODUCT_DELETED")
  void provisioningFailedRedeliveryIsIdempotentNoOp() {
    InventoryEventListener listener = new InventoryEventListener(catalog);
    UUID productId = UUID.randomUUID();
    InventoryEvent event = failedEvent(productId);
    doThrow(new ResourceNotFoundException("Product", productId)).when(catalog).delete(productId);

    // Must not throw - the "row is already gone" idempotency mechanism.
    listener.onInventoryEvent(event);
    // A second redelivery of the same event must also stay a safe no-op.
    listener.onInventoryEvent(event);

    verify(catalog, times(2)).delete(productId);
  }

  private static InventoryEvent failedEvent(UUID productId) {
    return new InventoryEvent(
        UUID.randomUUID(),
        InventoryEventType.INVENTORY_PROVISIONING_FAILED,
        productId,
        Instant.parse("2026-01-01T00:00:00Z"),
        null,
        25,
        "connection reset");
  }

  private static InventoryEvent event(
      InventoryEventType type, UUID productId, Integer newQuantity) {
    return new InventoryEvent(
        UUID.randomUUID(),
        type,
        productId,
        Instant.parse("2026-01-01T00:00:00Z"),
        newQuantity,
        null,
        null);
  }
}
