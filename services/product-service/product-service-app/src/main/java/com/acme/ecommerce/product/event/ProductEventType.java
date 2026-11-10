package com.acme.ecommerce.product.event;

/**
 * Lifecycle events published by the product service to Kafka.
 */
public enum ProductEventType {
  PRODUCT_CREATED,
  PRODUCT_UPDATED,
  PRODUCT_DELETED
}
