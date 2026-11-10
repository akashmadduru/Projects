/**
 * inventory-service - the platform's stock-level service (Phase 2).
 *
 * <p>Layering, matching product-service: {@code web} binds and delegates, {@code service} owns the
 * transaction boundary, {@code repository} talks to the database, {@code domain} holds the entity.
 * No business logic in a controller and none in a repository.
 *
 * <p>{@code event} (Phase 3) holds the Kafka consumer/publisher side: {@code ProductEventListener}
 * keeps {@code inventory.stock_levels} eventually consistent with {@code product.products} by
 * reacting to {@code product.events}, calling only {@code
 * InventoryService.provisionWithQuantity}/{@code removeIfPresent}/{@code
 * denormalizeProductSnapshot} - never {@code InventoryController}, which remains reachable
 * exclusively through the HTTP edge. {@code InventoryEventPublisher} publishes the reverse
 * direction, {@code inventory.events}, including the choreographed creation-saga's compensation
 * trigger, {@code INVENTORY_PROVISIONING_FAILED} (docs/HLD-catalog-enrichment-and-saga.md S5).
 */
package com.acme.ecommerce.inventory;
