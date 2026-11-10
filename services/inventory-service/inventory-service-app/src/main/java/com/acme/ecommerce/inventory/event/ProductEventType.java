package com.acme.ecommerce.inventory.event;

/**
 * The lifecycle events product-service publishes to {@code product.events}.
 *
 * <p>MUST STAY IN SYNC, BY NAME, with product-service's own {@code
 * com.acme.ecommerce.product.event.ProductEventType} - a deliberately separate, duplicated copy
 * (see HLD S2 Non-Goals and LLD S2.2), not a shared dependency.
 */
public enum ProductEventType {
  PRODUCT_CREATED,
  PRODUCT_UPDATED,
  PRODUCT_DELETED
}
