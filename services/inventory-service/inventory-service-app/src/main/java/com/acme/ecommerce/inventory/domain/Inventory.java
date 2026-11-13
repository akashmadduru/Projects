package com.acme.ecommerce.inventory.domain;

import com.acme.ecommerce.common.core.error.DomainRuleViolationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A stock-level row.
 *
 * <p>NO {@code @PrePersist}/{@code @PreUpdate}, AND NO {@code Instant.now()} ANYWHERE IN THIS CLASS
 * - deliberately, from day one, unlike {@code Product.java}'s current lifecycle-callback
 * anti-pattern (see LLD S2.1's "TimeProvider fix" note). JPA lifecycle callbacks cannot receive
 * constructor-injected dependencies, so the timestamp assignment lives in the service layer, which
 * already holds a {@code TimeProvider}: {@code now} is an explicit parameter callers pass to {@link
 * #builder()} and to {@link #adjustQuantity(int, Instant)} instead of an implicit callback side
 * effect.
 *
 * <p>UNIQUE {@code product_id} - this constraint IS the idempotency mechanism a future {@code
 * PRODUCT_CREATED} redelivery relies on (see LLD Edge Cases table); it is enforced here purely as
 * ordinary data integrity, independent of any Kafka wiring.
 *
 * <p>EQUALITY IS KEYED ON {@code id} ONLY, via {@code @EqualsAndHashCode(onlyExplicitlyIncluded =
 * true)} - the standard JPA+Lombok pattern. Note the one accepted caveat: {@code hashCode()} is
 * {@code id}-derived, so it changes when a transient instance is first flushed - do not put a
 * not-yet-persisted {@code Inventory} into a {@code HashSet}/{@code HashMap} and persist it
 * afterward. No code in this service does that today. Construction goes through Lombok's generated
 * builder, never a hand-rolled constructor.
 */
@Entity
@Table(name = "stock_levels")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Inventory {

  /**
   * Matches {@code low_stock_threshold}'s DB {@code DEFAULT 10} (V2 migration) and the service
   * layer's "null on the wire means use the default" handling for {@code CreateInventoryRequest}/
   * {@code UpdateInventoryRequest} - one constant, referenced from both places, so the two never
   * drift apart.
   */
  public static final int DEFAULT_LOW_STOCK_THRESHOLD = 10;

  @EqualsAndHashCode.Include
  @ToString.Include
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ToString.Include
  @Column(name = "product_id", nullable = false, unique = true)
  private UUID productId;

  @ToString.Include
  @Column(name = "quantity", nullable = false)
  private int quantity;

  // NEW (Phase 3 slice, schema enrichment only - see
  // docs/architecture/design/HLD-catalog-enrichment-and-saga.md
  // S2/S4).
  // Nullable, forward-compatible single-warehouse label - not a real multi-warehouse model.
  @Column(name = "warehouse_code", length = 32)
  private String warehouseCode;

  // Denormalized from product-service. Populated ONLY via applyProductSnapshot below, called from
  // ProductEventListener's PRODUCT_UPDATED consumption
  // (docs/architecture/design/HLD-catalog-enrichment-and-saga.md
  // S2, closing Phase 2 Open Question #4). Null until the first such event is consumed for this
  // productId.
  @Column(name = "product_name", length = 200)
  private String productName;

  // Same as productName above; same source and same caveat.
  @Column(name = "product_sku", length = 64)
  private String productSku;

  // NEW, NOT NULL with an in-code default matching the DB DEFAULT (V2 migration) so a
  // programmatically-constructed row and a direct-insert row agree without a backfill.
  @Builder.Default
  @Column(name = "low_stock_threshold", nullable = false)
  private int lowStockThreshold = DEFAULT_LOW_STOCK_THRESHOLD;

  // NEW (Phase 4 slice, schema enrichment only).
  // Reserved quantity (items allocated but not yet shipped). Defaults to 0.
  @Builder.Default
  @Column(name = "reserved_quantity", nullable = false)
  private int reservedQuantity = 0;

  // Inventory status: IN_STOCK, LOW_STOCK, OUT_OF_STOCK. Nullable initially.
  @Column(name = "status", length = 32)
  private String status;

  // Timestamp of last inventory update. Nullable initially; updated whenever
  // quantity, reserved_quantity, or status changes.
  @Column(name = "last_updated_at")
  private Instant lastUpdatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /**
   * Sets the quantity on hand.
   *
   * @param newQuantity units currently on hand; never negative
   * @param now the current instant, supplied by the caller's {@code TimeProvider}
   * @throws DomainRuleViolationException if {@code newQuantity} is negative. Defense-in-depth: the
   *     database {@code CHECK (quantity >= 0)} constraint would also reject this at flush, but a
   *     domain exception here produces a clean 422 before any statement is sent.
   */
  public void adjustQuantity(int newQuantity, Instant now) {
    if (newQuantity < 0) {
      throw new DomainRuleViolationException("Quantity cannot be negative");
    }
    this.quantity = newQuantity;
    this.updatedAt = now;
  }

  /**
   * Updates the caller-supplied enrichment fields (as opposed to {@code productName}/{@code
   * productSku}, which are not settable in this pass - see the field comments above). Called by
   * {@code InventoryService.update()}, the direct {@code PUT /api/inventory/{id}} path; full
   * replacement, not a merge - a {@code null warehouseCode} on the request clears any previously
   * set value, matching this endpoint's existing full-resource-replace semantics for {@code
   * quantity}.
   *
   * @param warehouseCode a forward-compatible single-warehouse label, or {@code null}
   * @param lowStockThreshold the quantity at/below which this row is considered low stock
   * @param now the current instant, supplied by the caller's {@code TimeProvider}
   */
  public void updateEnrichment(String warehouseCode, int lowStockThreshold, Instant now) {
    this.warehouseCode = warehouseCode;
    this.lowStockThreshold = lowStockThreshold;
    this.updatedAt = now;
  }

  /**
   * Applies a denormalized product-name/SKU snapshot, deliberately narrow - cannot re-trigger
   * {@code inventory.events} publication (this class has zero Kafka dependencies, so there is no
   * code path by which calling it could publish anything, the same structural guarantee {@code
   * Product.applyInventorySignal} relies on for the reverse direction). Called ONLY by {@code
   * InventoryService.denormalizeProductSnapshot}, in reaction to a consumed {@code PRODUCT_UPDATED}
   * event - never by {@code InventoryController}.
   *
   * @param name the product's display name at publish time
   * @param sku the product's merchandising identifier at publish time
   * @param now the current instant, supplied by the caller's {@code TimeProvider}
   */
  public void applyProductSnapshot(String name, String sku, Instant now) {
    this.productName = name;
    this.productSku = sku;
    this.updatedAt = now;
  }

  /**
   * Updates reserved quantity and status tracking. Called by service layer in reaction to inventory
   * operations.
   *
   * @param newReservedQuantity the reserved quantity to set
   * @param newStatus the inventory status (IN_STOCK, LOW_STOCK, OUT_OF_STOCK)
   * @param now the current instant, supplied by the caller's {@code TimeProvider}
   * @throws DomainRuleViolationException if {@code newReservedQuantity} exceeds current quantity
   */
  public void updateStatus(int newReservedQuantity, String newStatus, Instant now) {
    if (newReservedQuantity > this.quantity) {
      throw new DomainRuleViolationException("Reserved quantity cannot exceed total quantity");
    }
    this.reservedQuantity = newReservedQuantity;
    this.status = newStatus;
    this.lastUpdatedAt = now;
    this.updatedAt = now;
  }
}
