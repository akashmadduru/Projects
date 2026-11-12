package com.acme.ecommerce.order.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** List-row view of an order. itemCount is the total quantity across lines. */
public record OrderSummaryResponse(
    UUID id,
    String status,
    String currency,
    BigDecimal totalAmount,
    Instant placedAt,
    long itemCount,
    String paymentMethod,
    String paymentStatus) {}
