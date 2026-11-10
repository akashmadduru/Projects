/**
 * Inventory service published contract (Phase 2).
 *
 * <p>Holds the DTOs that appear on inventory-service's controller signatures and the declarative
 * {@link com.acme.ecommerce.inventory.api.InventoryClient} a consuming service extends. Nothing
 * here may depend on the implementation module: a consumer must be able to compile against the
 * contract without inheriting inventory-service's persistence or security stack.
 */
package com.acme.ecommerce.inventory.api;
