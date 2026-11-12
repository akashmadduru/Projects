package com.acme.ecommerce.order.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AdminOrderSummary(
    UUID id,
    String status,
    String paymentMethod,
    String paymentStatus,
    String email,
    BigDecimal totalAmount,
    String currency,
    long itemCount,
    Instant placedAt) {}
