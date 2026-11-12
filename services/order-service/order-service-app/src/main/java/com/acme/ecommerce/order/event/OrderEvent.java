package com.acme.ecommerce.order.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Payload of the {@code order.events} topic (JSON, key = orderId). Times are ISO-8601 strings.
 *
 * <p>Phase 9 additions (additive): {@code shipments} (every shipment of the order),
 * {@code shipmentId} (SHIPMENT_STATUS_CHANGED: the shipment that moved; {@code status} and the
 * tracking fields are then that shipment's), {@code returnShipmentId}/{@code returnId}/{@code
 * refundAmount} (RETURN_* events).
 */
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
    String occurredAt,
    String paymentStatus,
    List<ShipmentInfo> shipments,
    UUID shipmentId,
    UUID returnId,
    UUID returnShipmentId,
    BigDecimal refundAmount) {

  /** One shipment of the order; {@code amount} = items value of that shipment (seller gross). */
  public record ShipmentInfo(
      UUID shipmentId,
      UUID sellerId,
      String sellerName,
      String status,
      BigDecimal amount,
      String trackingNumber,
      String carrier,
      String estimatedDeliveryDate,
      List<ShipmentItem> items) {}

  /** One line of a shipment; amount = line total. */
  public record ShipmentItem(UUID productId, String name, int quantity, BigDecimal amount) {}
}
