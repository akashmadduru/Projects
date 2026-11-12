package com.acme.ecommerce.notification.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * Consumer-side copy of the {@code order.events} payload (produced by order-service). Timestamps
 * and ids are kept as strings so a producer format change cannot make a message undeserializable.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEvent(
    String eventId,
    String type,
    String orderId,
    String userId,
    String email,
    String status,
    String paymentMethod,
    BigDecimal totalAmount,
    String currency,
    String trackingNumber,
    String carrier,
    String estimatedDeliveryDate,
    Integer itemCount,
    String firstItemName,
    String occurredAt) {}
