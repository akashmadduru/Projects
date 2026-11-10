package com.acme.ecommerce.product.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The message consumed from Kafka topic {@code rating.events}.
 *
 * <p>THE CONSUMER-SIDE DUPLICATE, deliberately not shared with rating-service's own copy of this
 * same shape (ADR 0002/0003, docs/HLD-catalog-enrichment-and-saga.md S2 Non-Goals and
 * docs/LLD-catalog-enrichment-and-saga.md S2.1) - there is one producer and one consumer of this
 * topic in this iteration; extracting a {@code libs/common-events} module is deferred until a
 * second consumer exists. {@code eventId}/{@code eventType}/{@code ratingId}/{@code productId}/
 * {@code publishedAt} match rating-service's producer-side {@code RatingEvent} field-for-field so
 * Jackson deserialises the wire JSON compatibly.
 *
 * <p>{@code payload} IS {@link RatingSnapshot}, NOT {@code RatingResponse}. product-service has
 * no dependency on {@code rating-service-api} - the event contract is the only coupling between
 * the two services. The consumer's {@code RatingEventListener} may evolve to compute or propagate
 * individual rating signals (ratingValue, helpfulness aggregate, etc.) into product denormalization;
 * this snapshot is structured to enable that evolution (see LLD-catalog-enrichment-and-saga.md S4).
 *
 * @param eventId unique id of this event, freshly generated at publish time
 * @param eventType which lifecycle transition occurred
 * @param ratingId the rating the event concerns
 * @param productId the product being rated; FK for denormalization target
 * @param publishedAt when the publish happened, per the producer's {@code TimeProvider}
 * @param payload the rating snapshot at publish time; {@code null} for {@link
 *     RatingEventType#RATING_DELETED}
 */
public record RatingEvent(
    UUID eventId,
    RatingEventType eventType,
    UUID ratingId,
    UUID productId,
    Instant publishedAt,
    RatingSnapshot payload) {}
