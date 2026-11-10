package com.acme.ecommerce.product.event;

/**
 * Lifecycle events published by the rating service to {@code rating.events}.
 *
 * <p>THE CONSUMER-SIDE DUPLICATE, deliberately not shared with rating-service's own copy of this
 * same enum (ADR 0002/0003, docs/HLD-catalog-enrichment-and-saga.md S2 Non-Goals and
 * docs/LLD-catalog-enrichment-and-saga.md S2.1) - there is one producer and one consumer of this
 * topic in this iteration; extracting a {@code libs/common-events} module is deferred until a
 * second consumer exists. The enum values MUST STAY IN SYNC, BY NAME, with rating-service's own
 * {@code com.acme.ecommerce.rating.event.RatingEventType}.
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
