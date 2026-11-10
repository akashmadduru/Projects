package com.acme.ecommerce.product.event;

/**
 * The lifecycle events inventory-service publishes to {@code inventory.events}.
 *
 * <p>MUST STAY IN SYNC, BY NAME, with inventory-service's own {@code
 * com.acme.ecommerce.inventory.event.InventoryEventType} - a deliberately separate, duplicated copy
 * (ADR 0002/0003, docs/HLD-catalog-enrichment-and-saga.md S2 Non-Goals), not a shared dependency.
 *
 * <p>{@code INVENTORY_PROVISIONING_FAILED} is the choreographed creation-saga's compensation
 * trigger (compensating {@code PRODUCT_CREATED{initialQuantity}} -> {@code provisionWithQuantity}
 * failure path -> {@code ProductCatalogService.delete}, see docs/LLD-catalog-enrichment-and-saga.md
 * S2.1/S5b).
 */
public enum InventoryEventType {
  INVENTORY_QUANTITY_CHANGED,
  INVENTORY_LOW_STOCK,
  INVENTORY_PROVISIONING_FAILED
}
