package com.acme.ecommerce.inventory.event;

/**
 * Lifecycle events published by the inventory service to Kafka.
 */
public enum InventoryEventType {
  INVENTORY_QUANTITY_CHANGED,
  INVENTORY_LOW_STOCK,
  INVENTORY_PROVISIONING_FAILED
}
