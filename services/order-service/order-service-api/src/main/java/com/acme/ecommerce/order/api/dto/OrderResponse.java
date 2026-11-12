package com.acme.ecommerce.order.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
    UUID id,
    String status,
    String currency,
    BigDecimal totalAmount,
    Instant placedAt,
    Instant cancelledAt,
    ShipToResponse shipTo,
    List<OrderItemResponse> items) {}
