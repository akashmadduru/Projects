package com.acme.ecommerce.inventory.service;

import com.acme.ecommerce.common.core.error.ResourceConflictException;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.inventory.api.dto.InventoryResponse;
import com.acme.ecommerce.inventory.domain.Inventory;
import com.acme.ecommerce.inventory.event.InventoryEventPublisher;
import com.acme.ecommerce.inventory.event.InventoryEventType;
import com.acme.ecommerce.inventory.repository.InventoryRepository;
import com.acme.ecommerce.inventory.domain.Platform;
import com.acme.ecommerce.inventory.domain.Platform;
import com.acme.ecommerce.inventory.repository.VariantStockRepository;
import com.acme.ecommerce.inventory.repository.WarehouseStockRepository;
import com.acme.ecommerce.inventory.repository.WarehouseStockRepository;
import com.acme.ecommerce.inventory.web.dto.CreateInventoryRequest;
import com.acme.ecommerce.inventory.web.dto.UpdateInventoryRequest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock-level CRUD.
 *
 * <p>Mirrors {@code ProductCatalogService}'s layering: the controller never sees an {@link
 * Inventory} entity, {@code readOnly = true} at the class level lets Hibernate skip dirty-checking
 * on the read paths, and each write method opens its own {@code @Transactional}, overriding the
 * class-level default.
 *
 * <p>{@code provisionWithQuantity}/{@code removeIfPresent}/{@code denormalizeProductSnapshot} are
 * called ONLY by {@code ProductEventListener}, never by {@code InventoryController} - the
 * event-driven path and the direct-CRUD path are both valid ways to reach {@code
 * inventory.stock_levels}, but only the former is reachable from Kafka and only the latter is
 * reachable from HTTP.
 *
 * <p>{@code create()}/{@code update()} (the direct HTTP write paths) publish {@code
 * INVENTORY_QUANTITY_CHANGED} (and, when the resulting quantity is at/below {@code
 * lowStockThreshold}, {@code INVENTORY_LOW_STOCK}) after commit - see {@link
 * InventoryEventPublisher}. {@code provisionWithQuantity} (the event-driven path reacting to {@code
 * PRODUCT_CREATED}, the choreographed creation-saga's entry point,
 * docs/LLD-catalog-enrichment-and-saga.md S2.2) does the same, now that it provisions at the
 * caller-requested starting quantity instead of always zero.
 */
@Service
@Transactional(readOnly = true)
public class InventoryService {

  private final InventoryRepository inventory;
  private final TimeProvider timeProvider;
  private final InventoryEventPublisher events;
  private final VariantStockRepository variantStock;
  private final WarehouseStockRepository warehouseStock;

  public InventoryService(
      InventoryRepository inventory,
      TimeProvider timeProvider,
      InventoryEventPublisher events,
      VariantStockRepository variantStock,
      WarehouseStockRepository warehouseStock) {
    this.variantStock = variantStock;
    this.warehouseStock = warehouseStock;
    this.inventory = inventory;
    this.timeProvider = timeProvider;
    this.events = events;
  }

  /**
   * One page of stock levels.
   *
   * @param pageable page number/size/sort, defaulted by the controller
   * @return the requested page, already mapped to the published DTO
   */
  public Page<InventoryResponse> list(Pageable pageable) {
    return inventory.findAll(pageable).map(InventoryService::toResponse);
  }

  /**
   * A single stock-level row.
   *
   * @param id the inventory row identifier
   * @return the row
   * @throws ResourceNotFoundException when no such row exists
   */
  public InventoryResponse findById(UUID id) throws ResourceNotFoundException {
    return inventory
        .findById(id)
        .map(InventoryService::toResponse)
        .orElseThrow(() -> new ResourceNotFoundException("Inventory", id));
  }

  /**
   * The stock-level row for a product, if one has been provisioned.
   *
   * <p>Backs {@code GET /api/inventory/by-product/{productId}}
   * (docs/LLD-catalog-enrichment-and-saga.md S2.2, S3) - the read-only endpoint product-service's
   * {@code CatalogAggregateService} calls through its {@code aggregateCb} circuit breaker
   * (docs/adr/0003, Decision 2). An unprovisioned {@code productId} 404s here exactly like every
   * other lookup in this service; turning that into "no inventory data" is the caller's decision,
   * not this one's - mirrors {@link #findById}'s own posture.
   *
   * @param productId the product to look up stock for
   * @return the matching row
   * @throws ResourceNotFoundException when no row has been provisioned for {@code productId}
   */
  public InventoryResponse findByProductId(UUID productId) {
    java.util.List<InventoryResponse.VariantQuantity> vq =
        variantStock.byProduct(productId, java.util.List.of()).stream()
            .map(v -> new InventoryResponse.VariantQuantity(v.variantId(), v.quantity()))
            .toList();
    InventoryResponse base =
        inventory
            .findByProductId(productId)
            .map(InventoryService::toResponse)
            .orElseGet(
                () -> {
                  if (vq.isEmpty()) {
                    throw new ResourceNotFoundException("Inventory", productId);
                  }
                  // Variant-only product: no product-level row, expose the variant breakdown.
                  InventoryResponse stub = new InventoryResponse();
                  stub.setProductId(productId);
                  stub.setQuantity(vq.stream().mapToInt(InventoryResponse.VariantQuantity::quantity).sum());
                  stub.setAvailableQuantity(stub.getQuantity());
                  return stub;
                });
    base.setVariants(vq);
    return base;
  }

  public java.util.List<InventoryResponse.VariantQuantity> variantQuantities(
      java.util.List<UUID> ids) {
    return ids.stream()
        .map(id -> variantStock.quantity(id).map(q -> new InventoryResponse.VariantQuantity(id, q)))
        .flatMap(java.util.Optional::stream)
        .toList();
  }

  @Transactional
  public void setVariantStock(UUID variantId, UUID productId, int quantity) {
    int oldQty = variantStock.quantity(variantId).orElse(0);
    boolean wasEmpty = variantStock.quantity(variantId).map(q -> q <= 0).orElse(true);
    variantStock.upsert(variantId, productId, quantity);
    if (productId != null) {
      warehouseStock.shift(productId, variantId, Platform.DEFAULT_WAREHOUSE_ID, quantity - oldQty);
    }
    if (wasEmpty && quantity > 0 && productId != null) {
      events.publishAfterCommit(
          InventoryEventType.INVENTORY_QUANTITY_CHANGED, productId, variantId, quantity);
    }
  }

  /**
   * Provisions a stock-level row directly (as opposed to via the event-driven path).
   *
   * <p>The existence check below is a check-then-act race like any other: a concurrent {@code POST}
   * for the same {@code productId} can pass it before either write lands. The {@code saveAndFlush}
   * + catch afterward is what actually closes that race, the same way {@link
   * #provisionIfAbsent(UUID)} and {@code ProductCatalogService.create()} do it - the {@code
   * UNIQUE(product_id)} DB constraint is the real guarantee; this just turns the loser's violation
   * into a clean 409 instead of an unhandled 500.
   *
   * @param request the product to provision and its starting quantity
   * @return the created row
   * @throws ResourceConflictException if a row for this {@code productId} already exists
   */
  @Transactional
  public InventoryResponse create(CreateInventoryRequest request) {
    if (inventory.findByProductId(request.getProductId()).isPresent()) {
      throw new ResourceConflictException(
          "Inventory already exists for productId " + request.getProductId());
    }
    Instant now = timeProvider.now();
    Inventory row =
        Inventory.builder()
            .productId(request.getProductId())
            .quantity(0)
            .createdAt(now)
            .updatedAt(now)
            .warehouseCode(request.getWarehouseCode())
            .lowStockThreshold(resolveLowStockThreshold(request.getLowStockThreshold()))
            .build();
    row.adjustQuantity(request.getQuantity(), now);
    Inventory saved;
    try {
      saved = inventory.saveAndFlush(row);
    } catch (DataIntegrityViolationException e) {
      throw new ResourceConflictException(
          "Inventory already exists for productId " + request.getProductId());
    }
    events.publishAfterCommit(InventoryEventType.INVENTORY_QUANTITY_CHANGED, saved);
    maybePublishLowStock(saved);
    warehouseStock.shift(saved.getProductId(), null, Platform.DEFAULT_WAREHOUSE_ID, saved.getQuantity());
    return toResponse(saved);
  }

  /**
   * Updates the quantity on an existing row.
   *
   * @param id the inventory row identifier
   * @param request the new quantity and the version the caller last read
   * @return the updated row
   * @throws ResourceNotFoundException when no such row exists
   * @throws ResourceConflictException when {@code request.version()} no longer matches the
   *     persisted row's current version. Checked explicitly, up front, rather than left to
   *     Hibernate's own {@code OptimisticLockException} on flush - both produce the same outcome,
   *     but the explicit check produces a clean 409 before any column is touched.
   */
  @Transactional
  public InventoryResponse update(UUID id, UpdateInventoryRequest request) {
    Inventory row =
        inventory.findById(id).orElseThrow(() -> new ResourceNotFoundException("Inventory", id));
    if (!Objects.equals(row.getVersion(), request.getVersion())) {
      throw new ResourceConflictException(
          "Inventory " + id + " has version " + row.getVersion() + ", not " + request.getVersion());
    }
    Instant now = timeProvider.now();
    int oldQuantity = row.getQuantity();
    row.adjustQuantity(request.getQuantity(), now);
    row.updateEnrichment(
        request.getWarehouseCode(), resolveLowStockThreshold(request.getLowStockThreshold()), now);
    // FLUSH BEFORE READING THE VERSION BACK. @Version is a Hibernate-managed field that is only
    // incremented in-memory at flush time; without this, the response returned from this method
    // would report the PRE-update version (flush would happen later, at transaction commit, after
    // toResponse() has already read the stale value) - a client acting on that response would send
    // the next PUT with a version that is already wrong.
    Inventory saved = inventory.saveAndFlush(row);
    warehouseStock.shift(
        saved.getProductId(), null, Platform.DEFAULT_WAREHOUSE_ID, saved.getQuantity() - oldQuantity);
    events.publishAfterCommit(InventoryEventType.INVENTORY_QUANTITY_CHANGED, saved);
    maybePublishLowStock(saved);
    return toResponse(saved);
  }

  /**
   * Deletes a stock-level row.
   *
   * @param id the inventory row identifier
   * @throws ResourceNotFoundException when no such row exists
   */
  @Transactional
  public void delete(UUID id) {
    Inventory row =
        inventory.findById(id).orElseThrow(() -> new ResourceNotFoundException("Inventory", id));
    inventory.delete(row);
  }

  /**
   * Provisions a stock-level row for {@code productId} at the caller-requested starting quantity,
   * if one does not already exist, denormalizing {@code name}/{@code sku} onto it in the same
   * write. Replaces the earlier zero-quantity-only {@code provisionIfAbsent} - the choreographed
   * creation-saga's entry point on the inventory side (docs/HLD-catalog-enrichment-and-saga.md S5,
   * docs/LLD-catalog-enrichment-and-saga.md S2.2). Called ONLY by {@code ProductEventListener} in
   * reaction to a consumed {@code PRODUCT_CREATED} event - never by {@code InventoryController}.
   *
   * <p>IDEMPOTENT BY CONSTRUCTION, twice over (LLD S2.2, S6 edge-case table) - EXACTLY THE SAME
   * MECHANISM {@code provisionIfAbsent} used, just parameterized by quantity/name/sku. The
   * existence check below absorbs the overwhelmingly common case - sequential at-least-once
   * redelivery of the same event - without a noisy constraint-violation log line, and without
   * re-applying a redelivered event's {@code quantity} (quantity is set once, at first successful
   * provisioning, never on redelivery - LLD S6 edge-case table). The {@code UNIQUE(product_id)} DB
   * constraint is the real idempotency guarantee underneath it: if two near-simultaneous deliveries
   * both pass the existence check (a rebalance/retry producing two concurrent consumer threads, not
   * two partitions holding the same key), the second {@code INSERT} loses the race and is caught
   * here as a no-op rather than surfacing as an unhandled exception.
   *
   * <p>A {@code quantity} of {@code null} (the caller omitted {@code initialQuantity}) defaults to
   * {@code 0}, preserving {@code provisionIfAbsent}'s exact prior behavior for that case.
   *
   * <p>Any exception other than the {@link DataIntegrityViolationException} race case above (e.g. a
   * genuine infrastructure failure while writing the row) is intentionally left to propagate to the
   * caller ({@code ProductEventListener}) rather than being absorbed here - that propagation IS the
   * choreographed creation-saga's compensation trigger (HLD S5b/S7.1): the listener catches it and
   * publishes {@code INVENTORY_PROVISIONING_FAILED}.
   *
   * @param productId the product to provision inventory for
   * @param quantity the caller-requested starting quantity, or {@code null} to default to {@code 0}
   * @param name the product's display name at publish time, denormalized onto the new row
   * @param sku the product's merchandising identifier at publish time, denormalized onto the new
   *     row
   */
  @Transactional
  public void provisionWithQuantity(UUID productId, Integer quantity, String name, String sku) {
    if (inventory.findByProductId(productId).isPresent()) {
      return; // idempotent no-op - the common sequential-redelivery case
    }
    int startingQuantity = quantity != null ? quantity : 0;
    Instant now = timeProvider.now();
    Inventory row =
        Inventory.builder().productId(productId).quantity(0).createdAt(now).updatedAt(now).build();
    row.adjustQuantity(startingQuantity, now);
    row.applyProductSnapshot(name, sku, now);
    Inventory saved;
    try {
      // saveAndFlush, NOT save - see ProductCatalogService.create()'s identical
      // comment: with a client-assigned @GeneratedValue(UUID) id, save() alone
      // may defer the INSERT to transaction commit, which happens after this
      // method (and its catch block) has already returned. Without the
      // explicit flush, a racing concurrent delivery's UNIQUE-constraint
      // violation would surface as a raw, uncaught
      // DataIntegrityViolationException instead of being absorbed here.
      saved = inventory.saveAndFlush(row);
    } catch (DataIntegrityViolationException e) {
      // Lost the race to a concurrent delivery of the same PRODUCT_CREATED
      // event. No-op: the other delivery already created the row.
      return;
    }
    warehouseStock.shift(productId, null, Platform.DEFAULT_WAREHOUSE_ID, saved.getQuantity());
    events.publishAfterCommit(InventoryEventType.INVENTORY_QUANTITY_CHANGED, saved);
    maybePublishLowStock(saved);
  }

  /**
   * Removes the stock-level row for {@code productId} if one exists. Called ONLY by {@code
   * ProductEventListener} in reaction to a consumed {@code PRODUCT_DELETED} event - never by {@code
   * InventoryController}.
   *
   * <p>Idempotent no-op if the row is already absent - e.g. a second delivery of the same {@code
   * PRODUCT_DELETED} event, or a delete for a {@code productId} that was never provisioned.
   *
   * @param productId the product whose inventory row should be removed
   */
  @Transactional
  public void removeIfPresent(UUID productId) {
    inventory.findByProductId(productId).ifPresent(inventory::delete);
    warehouseStock.deleteByProduct(productId);
  }

  /**
   * Applies a denormalized product-name/SKU snapshot to the matching stock-level row. Called ONLY
   * by {@code ProductEventListener}, in reaction to a consumed {@code PRODUCT_UPDATED} event
   * (docs/HLD-catalog-enrichment-and-saga.md S2, closing Phase 2 Open Question #4) - never by
   * {@code InventoryController}.
   *
   * <p>Does NOT call {@link InventoryEventPublisher} - quantity did not change, so publishing
   * {@code INVENTORY_QUANTITY_CHANGED} here would be both incorrect and would restart the ping-pong
   * loop this design is explicit about avoiding (the reverse-direction analog of {@code
   * Product.applyInventorySignal} never calling {@code ProductEventPublisher}).
   *
   * <p>IDEMPOTENT BY CONSTRUCTION: re-applying the same {@code name}/{@code sku} on redelivery is a
   * no-op overwrite with the same values, and {@link InventoryRepository#findByProductId(UUID)}
   * returning empty (the row has not been provisioned yet, or was already removed) is a safe,
   * silent no-op rather than an error - the event describes a product state this row either hasn't
   * caught up to yet or no longer needs to.
   *
   * @param productId the product whose stock-level row should be denormalized
   * @param name the product's display name at publish time
   * @param sku the product's merchandising identifier at publish time
   */
  @Transactional
  public void denormalizeProductSnapshot(UUID productId, String name, String sku) {
    inventory
        .findByProductId(productId)
        .ifPresent(row -> row.applyProductSnapshot(name, sku, timeProvider.now()));
  }

  /**
   * Publishes {@code INVENTORY_LOW_STOCK} when the row's current quantity is at or below its
   * low-stock threshold. No edge-detection/hysteresis (docs/HLD-catalog-enrichment-and-saga.md S11
   * Open Question #3) - a quantity that stays at or below the threshold across repeated writes
   * publishes repeatedly; accepted for this slice.
   *
   * @param row the just-saved stock-level row
   */
  /** Admin platform-stock write: moves the legacy platform totals by delta and publishes. */
  @Transactional
  public void shiftLegacyTotals(UUID productId, UUID variantId, int delta) {
    if (delta == 0) {
      return;
    }
    if (variantId != null) {
      boolean wasEmpty = variantStock.quantity(variantId).map(q -> q <= 0).orElse(true);
      int next = Math.max(0, variantStock.quantity(variantId).orElse(0) + delta);
      variantStock.upsert(variantId, productId, next);
      if (wasEmpty && next > 0) {
        events.publishAfterCommit(
            InventoryEventType.INVENTORY_QUANTITY_CHANGED, productId, variantId, next);
      }
      return;
    }
    Instant now = timeProvider.now();
    Inventory row = inventory.findByProductId(productId).orElse(null);
    if (row == null) {
      row = Inventory.builder().productId(productId).quantity(0).createdAt(now).updatedAt(now).build();
    }
    row.adjustQuantity(Math.max(0, row.getQuantity() + delta), now);
    Inventory saved = inventory.saveAndFlush(row);
    events.publishAfterCommit(InventoryEventType.INVENTORY_QUANTITY_CHANGED, saved);
    maybePublishLowStock(saved);
  }

  private void maybePublishLowStock(Inventory row) {
    if (row.getQuantity() <= row.getLowStockThreshold()) {
      events.publishAfterCommit(InventoryEventType.INVENTORY_LOW_STOCK, row);
    }
  }

  /**
   * Resolves the low-stock threshold a create/update request should apply. {@code null} on the wire
   * means "use the default" (matching {@link Inventory#DEFAULT_LOW_STOCK_THRESHOLD}, which mirrors
   * the V2 migration's {@code DEFAULT 10}) - an explicit {@code 0} is a real, distinct value and
   * must not be coerced to the default.
   *
   * @param requested the request's {@code lowStockThreshold}, possibly {@code null}
   * @return {@code requested} if present, otherwise the default
   */
  private static int resolveLowStockThreshold(Integer requested) {
    return requested != null ? requested : Inventory.DEFAULT_LOW_STOCK_THRESHOLD;
  }

  private static InventoryResponse toResponse(Inventory row) {
    return new InventoryResponse(
        row.getId(),
        row.getProductId(),
        row.getQuantity(),
        row.getReservedQuantity(),
        row.getQuantity() - row.getReservedQuantity(),
        row.getStatus(),
        row.getLowStockThreshold(),
        row.getWarehouseCode(),
        row.getProductName(),
        row.getProductSku(),
        row.getVersion(),
        row.getLastUpdatedAt(),
        row.getCreatedAt(),
        row.getUpdatedAt(),
        java.util.List.of());
  }
}
