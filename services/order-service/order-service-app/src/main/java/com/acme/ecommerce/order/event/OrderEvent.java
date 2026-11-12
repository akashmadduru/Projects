package com.acme.ecommerce.order.event;

import java.math.BigDecimal;
import java.util.UUID;

/** Payload of the {@code order.events} topic (JSON, key = orderId). Times are ISO-8601 strings. */
public record OrderEvent(
    UUID eventId,
    OrderEventType type,
    UUID orderId,
    UUID userId,
    String email,
    String status,
    String paymentMethod,
    BigDecimal totalAmount,
    String currency,
    String trackingNumber,
    String carrier,
    String estimatedDeliveryDate,
    long itemCount,
    String firstItemName,
    String occurredAt) {}
