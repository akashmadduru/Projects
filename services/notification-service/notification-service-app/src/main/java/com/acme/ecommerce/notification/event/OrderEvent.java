package com.acme.ecommerce.notification.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

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
    String occurredAt,
    String shipmentId,
    List<ShipmentInfo> shipments) {

  /** Phase 9: one shipment of a (possibly split) order. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShipmentInfo(
      String shipmentId, String sellerName, String status, String carrier, String trackingNumber) {}

  /** More than one parcel: per-shipment emails replace the order-level shipped/delivered ones. */
  public boolean isSplit() {
    return shipments != null && shipments.size() > 1;
  }
}
