package com.acme.ecommerce.rating.event;

/**
 * Lifecycle events published by the rating service to Kafka.
 *
 * <p>These enums are published as part of {@link RatingEvent} payloads, allowing downstream
 * consumers to react to rating changes (e.g., product-service's {@code RatingEventListener}
 * updating denormalized product ratings).
 */
public enum RatingEventType {
  RATING_ADDED,
  RATING_UPDATED,
  RATING_DELETED
}
