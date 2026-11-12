package com.acme.ecommerce.promotion.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/** Consumer-side copy of the {@code order.events} payload (ids as strings, unknown ignored). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEvent(
    String eventId,
    String type,
    String orderId,
    String userId,
    String status,
    String paymentStatus,
    BigDecimal totalAmount,
    String currency,
    String occurredAt) {}
