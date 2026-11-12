package com.acme.ecommerce.order.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A shipment as the owning seller sees it (ship-to for fulfilment, no customer e-mail). */
public record SellerShipmentResponse(
    UUID id,
    UUID orderId,
    String status,
    String trackingNumber,
    String carrier,
    LocalDate estimatedDeliveryDate,
    Instant deliveredAt,
    BigDecimal amount,
    String currency,
    Instant placedAt,
    String paymentMethod,
    String warehouseCode,
    ShipToResponse shipTo,
    List<OrderItemResponse> items,
    List<TimelineEntryResponse> timeline) {}
