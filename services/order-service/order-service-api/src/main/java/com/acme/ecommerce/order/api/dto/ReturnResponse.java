package com.acme.ecommerce.order.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReturnResponse(
    UUID id,
    UUID orderId,
    String status,
    String reason,
    String comment,
    List<ReturnItemResponse> items,
    BigDecimal refundAmount,
    Instant createdAt,
    Instant updatedAt,
    String adminNote) {

  public record ReturnItemResponse(UUID productId, String name, int quantity, BigDecimal unitPrice) {}
}
