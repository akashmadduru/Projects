package com.acme.ecommerce.order.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** One seller/warehouse parcel of an order. */
public record ShipmentResponse(
    UUID id,
    UUID sellerId,
    String sellerName,
    String status,
    String trackingNumber,
    String carrier,
    LocalDate estimatedDeliveryDate,
    Instant deliveredAt,
    BigDecimal amount,
    String warehouseCode,
    boolean returnable,
    List<OrderItemResponse> items,
    List<TimelineEntryResponse> timeline) {}
