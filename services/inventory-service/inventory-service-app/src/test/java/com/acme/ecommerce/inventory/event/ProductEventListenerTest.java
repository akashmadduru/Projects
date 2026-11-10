package com.acme.ecommerce.inventory.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.acme.ecommerce.inventory.service.InventoryService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the listener's dispatch logic - the exhaustive {@code switch} over {@link
 * ProductEventType} - with no Spring context and no running Kafka broker.
 *
 * <p>{@link InventoryService} is mocked: what is under test is which of its two event-driven
 * methods a given {@code eventType} routes to, not {@link InventoryService}'s own idempotency
 * decisions - those are covered by {@code InventoryServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class ProductEventListenerTest {

  @Mock private InventoryService inventoryService;
  @Mock private InventoryEventPublisher inventoryEvents;

  @Test
  @DisplayName(
      "PRODUCT_CREATED dispatches to provisionWithQuantity(productId, initialQuantity, name, sku)")
  void productCreatedProvisions() {
    ProductEventListener listener = new ProductEventListener(inventoryService, inventoryEvents);
    UUID productId = UUID.randomUUID();
    ProductEvent event =
        new ProductEvent(
            UUID.randomUUID(),
            ProductEventType.PRODUCT_CREATED,
            productId,
            Instant.parse("2026-01-01T00:00:00Z"),
            new ProductSnapshot("Widget", "SKU-01"),
            25);

    listener.onProductEvent(event);

    verify(inventoryService).provisionWithQuantity(productId, 25, "Widget", "SKU-01");
    verify(inventoryService, never()).removeIfPresent(eq(productId));
    verifyNoInteractions(inventoryEvents);
  }

  @Test
  @DisplayName("PRODUCT_CREATED with no payload dispatches with a null name/sku")
  void productCreatedWithNullPayloadDispatchesWithNullNameSku() {
    ProductEventListener listener = new ProductEventListener(inventoryService, inventoryEvents);
    UUID productId = UUID.randomUUID();
    ProductEvent event =
        new ProductEvent(
            UUID.randomUUID(),
            ProductEventType.PRODUCT_CREATED,
            productId,
            Instant.parse("2026-01-01T00:00:00Z"),
            null,
            null);

    listener.onProductEvent(event);

    verify(inventoryService).provisionWithQuantity(productId, null, null, null);
  }

  @Test
  @DisplayName(
      "PRODUCT_CREATED, when provisionWithQuantity fails, publishes INVENTORY_PROVISIONING_FAILED"
          + " and does not rethrow - the choreographed creation-saga's compensation trigger")
  void productCreatedCompensatesOnProvisioningFailure() {
    ProductEventListener listener = new ProductEventListener(inventoryService, inventoryEvents);
    UUID productId = UUID.randomUUID();
    ProductEvent event =
        new ProductEvent(
            UUID.randomUUID(),
            ProductEventType.PRODUCT_CREATED,
            productId,
            Instant.parse("2026-01-01T00:00:00Z"),
            new ProductSnapshot("Widget", "SKU-01"),
            25);
    doThrow(new RuntimeException("connection reset"))
        .when(inventoryService)
        .provisionWithQuantity(productId, 25, "Widget", "SKU-01");

    // Must not throw - terminal handling for this record, not rethrown.
    listener.onProductEvent(event);

    verify(inventoryEvents)
        .publishNow(
            eq(InventoryEventType.INVENTORY_PROVISIONING_FAILED),
            eq(productId),
            eq(25),
            eq("connection reset"));
  }

  @Test
  @DisplayName("PRODUCT_DELETED dispatches to removeIfPresent(productId)")
  void productDeletedRemoves() {
    ProductEventListener listener = new ProductEventListener(inventoryService, inventoryEvents);
    UUID productId = UUID.randomUUID();
    ProductEvent event = event(ProductEventType.PRODUCT_DELETED, productId);

    listener.onProductEvent(event);

    verify(inventoryService).removeIfPresent(eq(productId));
    verify(inventoryService, never()).provisionWithQuantity(eq(productId), any(), any(), any());
    verifyNoInteractions(inventoryEvents);
  }

  @Test
  @DisplayName(
      "PRODUCT_UPDATED dispatches to denormalizeProductSnapshot(productId, name, sku) - now"
          + " load-bearing (Phase 2 Open Question #4, closed)")
  void productUpdatedDenormalizesSnapshot() {
    ProductEventListener listener = new ProductEventListener(inventoryService, inventoryEvents);
    UUID productId = UUID.randomUUID();
    ProductEvent event =
        new ProductEvent(
            UUID.randomUUID(),
            ProductEventType.PRODUCT_UPDATED,
            productId,
            Instant.parse("2026-01-01T00:00:00Z"),
            new ProductSnapshot("Widget", "SKU-01"),
            null);

    listener.onProductEvent(event);

    verify(inventoryService).denormalizeProductSnapshot(productId, "Widget", "SKU-01");
    verify(inventoryService, never()).provisionWithQuantity(eq(productId), any(), any(), any());
    verify(inventoryService, never()).removeIfPresent(eq(productId));
  }

  @Test
  @DisplayName("PRODUCT_UPDATED with a null payload no-ops rather than throwing")
  void productUpdatedWithNullPayloadIsSafeNoOp() {
    ProductEventListener listener = new ProductEventListener(inventoryService, inventoryEvents);
    ProductEvent event = event(ProductEventType.PRODUCT_UPDATED, UUID.randomUUID());

    listener.onProductEvent(event);

    verifyNoInteractions(inventoryService);
  }

  private static ProductEvent event(ProductEventType type, UUID productId) {
    return new ProductEvent(
        UUID.randomUUID(), type, productId, Instant.parse("2026-01-01T00:00:00Z"), null, null);
  }
}
