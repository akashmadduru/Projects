package com.acme.ecommerce.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.ResourceConflictException;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.time.FixedTimeProvider;
import com.acme.ecommerce.inventory.api.dto.InventoryResponse;
import com.acme.ecommerce.inventory.domain.Inventory;
import com.acme.ecommerce.inventory.event.InventoryEventPublisher;
import com.acme.ecommerce.inventory.event.InventoryEventType;
import com.acme.ecommerce.inventory.repository.InventoryRepository;
import com.acme.ecommerce.inventory.web.dto.CreateInventoryRequest;
import com.acme.ecommerce.inventory.web.dto.UpdateInventoryRequest;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for the stock-level service's write-path decisions.
 *
 * <p>No Spring context: the repository is mocked, so what is under test is the service's own
 * decisions - duplicate productId means {@link ResourceConflictException}, a stale version means
 * the same, an absent row means {@link ResourceNotFoundException} - rather than anything Spring or
 * Hibernate would already enforce. The full HTTP round trip, including RBAC, is covered by {@code
 * InventoryEndpointsIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Mock private InventoryRepository repository;
  @Mock private InventoryEventPublisher events;

  // Built in @BeforeEach, NOT as a field initializer: a field initializer runs during instance
  // construction, before MockitoExtension injects @Mock fields, so `repository`/`events` would
  // still be null at that point.
  private InventoryService service;

  @BeforeEach
  void setUp() {
    service = new InventoryService(repository, new FixedTimeProvider(NOW), events);
  }

  @Test
  @DisplayName("an unknown id raises RESOURCE_NOT_FOUND carrying the looked-up identifier")
  void unknownIdProducesNotFoundProblem() {
    UUID missing = UUID.fromString("00000000-0000-4000-8000-0000000000ff");
    when(repository.findById(missing)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.findById(missing))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Inventory")
        .hasMessageContaining(missing.toString());
  }

  @Nested
  @DisplayName("findByProductId() - GET /api/inventory/by-product/{productId}")
  class FindByProductId {

    @Test
    @DisplayName("returns the row for a productId that has been provisioned")
    void returnsRowWhenPresent() throws Exception {
      UUID productId = UUID.randomUUID();
      Inventory row = newTestInventory(productId, NOW);
      row.adjustQuantity(7, NOW);
      when(repository.findByProductId(productId)).thenReturn(Optional.of(row));

      InventoryResponse response = service.findByProductId(productId);

      assertThat(response.productId()).isEqualTo(productId);
      assertThat(response.quantity()).isEqualTo(7);
    }

    @Test
    @DisplayName("an unprovisioned productId raises RESOURCE_NOT_FOUND, not an empty 200")
    void unprovisionedProductIdRaisesNotFound() {
      UUID productId = UUID.randomUUID();
      when(repository.findByProductId(productId)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.findByProductId(productId))
          .isInstanceOf(ResourceNotFoundException.class)
          .hasMessageContaining("Inventory")
          .hasMessageContaining(productId.toString());
    }
  }

  @Test
  @DisplayName("create() provisions a zero-quantity row then applies the requested quantity")
  void createProvisionsRow() {
    UUID productId = UUID.randomUUID();
    when(repository.findByProductId(productId)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(Inventory.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    InventoryResponse response =
        service.create(new CreateInventoryRequest(productId, 25, null, null));

    assertThat(response.productId()).isEqualTo(productId);
    assertThat(response.quantity()).isEqualTo(25);
  }

  @Test
  @DisplayName(
      "create() publishes INVENTORY_QUANTITY_CHANGED after the write, quantity above the"
          + " low-stock threshold does not also publish INVENTORY_LOW_STOCK")
  void createPublishesQuantityChangedOnly() {
    UUID productId = UUID.randomUUID();
    when(repository.findByProductId(productId)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(Inventory.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.create(new CreateInventoryRequest(productId, 25, null, null));

    verify(events)
        .publishAfterCommit(
            eq(InventoryEventType.INVENTORY_QUANTITY_CHANGED), any(Inventory.class));
    verify(events, never())
        .publishAfterCommit(eq(InventoryEventType.INVENTORY_LOW_STOCK), any(Inventory.class));
  }

  @Test
  @DisplayName(
      "create() also publishes INVENTORY_LOW_STOCK when the starting quantity is at/below the"
          + " low-stock threshold")
  void createAlsoPublishesLowStockWhenAtOrBelowThreshold() {
    UUID productId = UUID.randomUUID();
    when(repository.findByProductId(productId)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(Inventory.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.create(new CreateInventoryRequest(productId, 3, null, 5));

    verify(events)
        .publishAfterCommit(
            eq(InventoryEventType.INVENTORY_QUANTITY_CHANGED), any(Inventory.class));
    verify(events)
        .publishAfterCommit(eq(InventoryEventType.INVENTORY_LOW_STOCK), any(Inventory.class));
  }

  @Test
  @DisplayName("create() never publishes when the write itself fails")
  void createDoesNotPublishOnConflict() {
    UUID productId = UUID.randomUUID();
    when(repository.findByProductId(productId))
        .thenReturn(Optional.of(newTestInventory(productId, NOW)));

    assertThatThrownBy(() -> service.create(new CreateInventoryRequest(productId, 10, null, null)))
        .isInstanceOf(ResourceConflictException.class);

    verifyNoInteractions(events);
  }

  @Test
  @DisplayName("create() applies the requested warehouseCode/lowStockThreshold when supplied")
  void createAppliesEnrichmentFieldsWhenSupplied() {
    UUID productId = UUID.randomUUID();
    when(repository.findByProductId(productId)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(Inventory.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    InventoryResponse response =
        service.create(new CreateInventoryRequest(productId, 25, "WH-1", 5));

    assertThat(response.warehouseCode()).isEqualTo("WH-1");
    assertThat(response.lowStockThreshold()).isEqualTo(5);
  }

  @Test
  @DisplayName("create() defaults lowStockThreshold to 10 when omitted")
  void createDefaultsLowStockThresholdWhenOmitted() {
    UUID productId = UUID.randomUUID();
    when(repository.findByProductId(productId)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(Inventory.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    InventoryResponse response =
        service.create(new CreateInventoryRequest(productId, 25, null, null));

    assertThat(response.lowStockThreshold()).isEqualTo(10);
  }

  @Test
  @DisplayName("create() rejects a second row for a productId that already has one")
  void createRejectsDuplicateProductId() {
    UUID productId = UUID.randomUUID();
    when(repository.findByProductId(productId))
        .thenReturn(Optional.of(newTestInventory(productId, NOW)));

    assertThatThrownBy(() -> service.create(new CreateInventoryRequest(productId, 10, null, null)))
        .isInstanceOf(ResourceConflictException.class);
  }

  @Test
  @DisplayName("update() rejects a stale version before touching any column")
  void updateRejectsStaleVersion() throws Exception {
    UUID id = UUID.randomUUID();
    Inventory row = newTestInventory(UUID.randomUUID(), NOW);
    setVersion(row, 3L);
    when(repository.findById(id)).thenReturn(Optional.of(row));

    assertThatThrownBy(() -> service.update(id, new UpdateInventoryRequest(50, 1L, null, null)))
        .isInstanceOf(ResourceConflictException.class);

    // The stale write must never land - quantity is unchanged.
    assertThat(row.getQuantity()).isZero();
    verifyNoInteractions(events);
  }

  @Test
  @DisplayName("update() applies the new quantity when the version matches")
  void updateAppliesMatchingVersion() throws Exception {
    UUID id = UUID.randomUUID();
    Inventory row = newTestInventory(UUID.randomUUID(), NOW);
    setVersion(row, 0L);
    when(repository.findById(id)).thenReturn(Optional.of(row));
    when(repository.saveAndFlush(row)).thenReturn(row);

    InventoryResponse response = service.update(id, new UpdateInventoryRequest(50, 0L, null, null));

    assertThat(response.quantity()).isEqualTo(50);
  }

  @Test
  @DisplayName(
      "update() publishes INVENTORY_QUANTITY_CHANGED after commit, and also"
          + " INVENTORY_LOW_STOCK when the new quantity is at/below the threshold")
  void updatePublishesQuantityChangedAndLowStock() throws Exception {
    UUID id = UUID.randomUUID();
    Inventory row = newTestInventory(UUID.randomUUID(), NOW, null, 10);
    setVersion(row, 0L);
    when(repository.findById(id)).thenReturn(Optional.of(row));
    when(repository.saveAndFlush(row)).thenReturn(row);

    service.update(id, new UpdateInventoryRequest(5, 0L, null, 10));

    verify(events).publishAfterCommit(InventoryEventType.INVENTORY_QUANTITY_CHANGED, row);
    verify(events).publishAfterCommit(InventoryEventType.INVENTORY_LOW_STOCK, row);
  }

  @Test
  @DisplayName(
      "update() does not publish INVENTORY_LOW_STOCK when the new quantity is above the"
          + " threshold")
  void updateDoesNotPublishLowStockAboveThreshold() throws Exception {
    UUID id = UUID.randomUUID();
    Inventory row = newTestInventory(UUID.randomUUID(), NOW, null, 10);
    setVersion(row, 0L);
    when(repository.findById(id)).thenReturn(Optional.of(row));
    when(repository.saveAndFlush(row)).thenReturn(row);

    service.update(id, new UpdateInventoryRequest(50, 0L, null, 10));

    verify(events).publishAfterCommit(InventoryEventType.INVENTORY_QUANTITY_CHANGED, row);
    verify(events, never()).publishAfterCommit(eq(InventoryEventType.INVENTORY_LOW_STOCK), any());
  }

  @Test
  @DisplayName("update() applies the requested warehouseCode/lowStockThreshold when supplied")
  void updateAppliesEnrichmentFieldsWhenSupplied() throws Exception {
    UUID id = UUID.randomUUID();
    Inventory row = newTestInventory(UUID.randomUUID(), NOW);
    setVersion(row, 0L);
    when(repository.findById(id)).thenReturn(Optional.of(row));
    when(repository.saveAndFlush(row)).thenReturn(row);

    InventoryResponse response = service.update(id, new UpdateInventoryRequest(50, 0L, "WH-2", 7));

    assertThat(response.warehouseCode()).isEqualTo("WH-2");
    assertThat(response.lowStockThreshold()).isEqualTo(7);
  }

  @Test
  @DisplayName("update() defaults lowStockThreshold to 10 when omitted")
  void updateDefaultsLowStockThresholdWhenOmitted() throws Exception {
    UUID id = UUID.randomUUID();
    Inventory row = newTestInventory(UUID.randomUUID(), NOW);
    setVersion(row, 0L);
    when(repository.findById(id)).thenReturn(Optional.of(row));
    when(repository.saveAndFlush(row)).thenReturn(row);

    InventoryResponse response = service.update(id, new UpdateInventoryRequest(50, 0L, null, null));

    assertThat(response.lowStockThreshold()).isEqualTo(10);
  }

  /** {@code @Version} is Hibernate-managed; a plain unit test has to set it via reflection. */
  private static void setVersion(Inventory row, Long version) throws Exception {
    Field field = Inventory.class.getDeclaredField("version");
    field.setAccessible(true);
    field.set(row, version);
  }

  /**
   * {@link InventoryService#provisionWithQuantity(UUID, Integer, String, String)}/{@link
   * InventoryService#removeIfPresent(UUID)} are called ONLY by {@code ProductEventListener} (see
   * that class's own tests for dispatch), but their idempotency is exactly the kind of decision
   * this repository-mocked, no-Spring-context style of test is built to prove without a running
   * Kafka broker.
   */
  @Nested
  @DisplayName(
      "provisionWithQuantity()/removeIfPresent() - the event-driven, idempotent write path")
  class EventDrivenProvisioning {

    @Test
    @DisplayName(
        "provisions a row at the requested quantity, denormalized with name/sku, when none exists"
            + " yet for the productId")
    void provisionsWhenAbsent() {
      UUID productId = UUID.randomUUID();
      when(repository.findByProductId(productId)).thenReturn(Optional.empty());
      when(repository.saveAndFlush(any(Inventory.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      service.provisionWithQuantity(productId, 25, "Widget", "SKU-01");

      ArgumentCaptor<Inventory> captor = ArgumentCaptor.forClass(Inventory.class);
      verify(repository).saveAndFlush(captor.capture());
      Inventory saved = captor.getValue();
      assertThat(saved.getQuantity()).isEqualTo(25);
      assertThat(saved.getProductName()).isEqualTo("Widget");
      assertThat(saved.getProductSku()).isEqualTo("SKU-01");
      verify(events)
          .publishAfterCommit(
              eq(InventoryEventType.INVENTORY_QUANTITY_CHANGED), any(Inventory.class));
    }

    @Test
    @DisplayName("defaults to a zero quantity when the caller omitted initialQuantity")
    void defaultsToZeroWhenQuantityOmitted() {
      UUID productId = UUID.randomUUID();
      when(repository.findByProductId(productId)).thenReturn(Optional.empty());
      when(repository.saveAndFlush(any(Inventory.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      service.provisionWithQuantity(productId, null, "Widget", "SKU-01");

      ArgumentCaptor<Inventory> captor = ArgumentCaptor.forClass(Inventory.class);
      verify(repository).saveAndFlush(captor.capture());
      assertThat(captor.getValue().getQuantity()).isZero();
    }

    @Test
    @DisplayName(
        "also publishes INVENTORY_LOW_STOCK when the requested starting quantity is at/below"
            + " the default low-stock threshold")
    void publishesLowStockWhenStartingQuantityIsLow() {
      UUID productId = UUID.randomUUID();
      when(repository.findByProductId(productId)).thenReturn(Optional.empty());
      when(repository.saveAndFlush(any(Inventory.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      service.provisionWithQuantity(productId, 3, "Widget", "SKU-01");

      verify(events)
          .publishAfterCommit(eq(InventoryEventType.INVENTORY_LOW_STOCK), any(Inventory.class));
    }

    @Test
    @DisplayName(
        "no-ops when a row already exists - the common sequential-redelivery case; a redelivered"
            + " quantity is never re-applied")
    void noOpsWhenRowAlreadyExists() {
      UUID productId = UUID.randomUUID();
      when(repository.findByProductId(productId))
          .thenReturn(Optional.of(newTestInventory(productId, NOW)));

      service.provisionWithQuantity(productId, 25, "Widget", "SKU-01");

      verify(repository, never()).save(any(Inventory.class));
      verify(repository, never()).saveAndFlush(any(Inventory.class));
      verifyNoInteractions(events);
    }

    @Test
    @DisplayName(
        "absorbs a DataIntegrityViolationException as a no-op - the concurrent-delivery race"
            + " that the existence check alone cannot catch")
    void absorbsConstraintViolationFromConcurrentDelivery() {
      UUID productId = UUID.randomUUID();
      when(repository.findByProductId(productId)).thenReturn(Optional.empty());
      when(repository.saveAndFlush(any(Inventory.class)))
          .thenThrow(new DataIntegrityViolationException("duplicate key value: product_id"));

      // Must not throw - a losing racer must never surface as an unhandled
      // exception on the consumer thread.
      service.provisionWithQuantity(productId, 25, "Widget", "SKU-01");

      verify(repository).saveAndFlush(any(Inventory.class));
      verifyNoInteractions(events);
    }

    @Test
    @DisplayName(
        "a genuine infrastructure failure (anything other than the concurrent-delivery race"
            + " above) propagates to the caller - ProductEventListener's compensation trigger")
    void propagatesGenuineInfrastructureFailure() {
      UUID productId = UUID.randomUUID();
      when(repository.findByProductId(productId)).thenReturn(Optional.empty());
      when(repository.saveAndFlush(any(Inventory.class)))
          .thenThrow(new RuntimeException("connection reset"));

      assertThatThrownBy(() -> service.provisionWithQuantity(productId, 25, "Widget", "SKU-01"))
          .isInstanceOf(RuntimeException.class)
          .hasMessage("connection reset");

      verifyNoInteractions(events);
    }

    @Test
    @DisplayName("removes the row when one exists for the productId")
    void removesWhenPresent() {
      UUID productId = UUID.randomUUID();
      Inventory row = newTestInventory(productId, NOW);
      when(repository.findByProductId(productId)).thenReturn(Optional.of(row));

      service.removeIfPresent(productId);

      verify(repository).delete(row);
    }

    @Test
    @DisplayName(
        "no-ops when no row exists for the productId - already removed or never provisioned")
    void noOpsWhenRowAbsent() {
      UUID productId = UUID.randomUUID();
      when(repository.findByProductId(productId)).thenReturn(Optional.empty());

      service.removeIfPresent(productId);

      verify(repository, never()).delete(any(Inventory.class));
    }
  }

  /**
   * {@link InventoryService#denormalizeProductSnapshot(UUID, String, String)} is called ONLY by
   * {@code ProductEventListener} in reaction to a consumed {@code PRODUCT_UPDATED} event (see that
   * class's own tests for dispatch) - covered here the same repository-mocked, no-Spring-context
   * way as {@code EventDrivenProvisioning} above.
   */
  @Nested
  @DisplayName("denormalizeProductSnapshot() - PRODUCT_UPDATED consumption, now load-bearing")
  class DenormalizeProductSnapshot {

    @Test
    @DisplayName("applies name/sku onto the matching row and never publishes to inventory.events")
    void appliesSnapshotWhenRowPresent() {
      UUID productId = UUID.randomUUID();
      Inventory row = newTestInventory(productId, NOW);
      when(repository.findByProductId(productId)).thenReturn(Optional.of(row));

      service.denormalizeProductSnapshot(productId, "Widget", "SKU-01");

      assertThat(row.getProductName()).isEqualTo("Widget");
      assertThat(row.getProductSku()).isEqualTo("SKU-01");
      // The "no ping-pong" guarantee: quantity did not change, so nothing on
      // inventory.events should ever be published from this path.
      verifyNoInteractions(events);
    }

    @Test
    @DisplayName(
        "no-ops when no row exists yet for the productId - the row hasn't been provisioned yet,"
            + " or was already removed")
    void noOpsWhenRowAbsent() {
      UUID productId = UUID.randomUUID();
      when(repository.findByProductId(productId)).thenReturn(Optional.empty());

      // Must not throw.
      service.denormalizeProductSnapshot(productId, "Widget", "SKU-01");

      verifyNoInteractions(events);
    }
  }

  private static Inventory newTestInventory(UUID productId, Instant now) {
    return newTestInventory(productId, now, null, Inventory.DEFAULT_LOW_STOCK_THRESHOLD);
  }

  private static Inventory newTestInventory(
      UUID productId, Instant now, String warehouseCode, int lowStockThreshold) {
    return Inventory.builder()
        .productId(productId)
        .quantity(0)
        .createdAt(now)
        .updatedAt(now)
        .warehouseCode(warehouseCode)
        .lowStockThreshold(lowStockThreshold)
        .build();
  }
}
