package com.acme.ecommerce.order.service;

import com.acme.ecommerce.order.api.dto.OrderItemResponse;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.api.dto.ReturnResponse;
import com.acme.ecommerce.order.api.dto.SellerShipmentResponse;
import com.acme.ecommerce.order.api.dto.ShipmentResponse;
import com.acme.ecommerce.order.api.dto.ShipToResponse;
import com.acme.ecommerce.order.api.dto.TaxBreakdownResponse;
import com.acme.ecommerce.order.api.dto.TimelineEntryResponse;
import com.acme.ecommerce.order.config.OrderProperties;
import com.acme.ecommerce.order.domain.Order;
import com.acme.ecommerce.order.domain.OrderItem;
import com.acme.ecommerce.order.domain.OrderReturn;
import com.acme.ecommerce.order.domain.OrderShipment;
import com.acme.ecommerce.order.domain.OrderStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Entity to DTO mapping. Must be called inside a transaction (touches lazy collections). */
@Component
public class OrderMapper {

  private final OrderProperties props;

  public OrderMapper(OrderProperties props) {
    this.props = props;
  }

  public OrderResponse toResponse(Order o) {
    int window = props.getReturns().getWindowDays();
    Instant now = Instant.now();
    boolean hasBreakdown =
        o.getCgstAmount() != null || o.getSgstAmount() != null || o.getIgstAmount() != null;
    return new OrderResponse(
        o.getId(),
        o.getStatus().name(),
        o.getCurrency(),
        o.getTotalAmount(),
        o.getPlacedAt(),
        o.getCancelledAt(),
        new ShipToResponse(
            o.getShipFullName(),
            o.getShipPhone(),
            o.getShipLine1(),
            o.getShipLine2(),
            o.getShipCity(),
            o.getShipState(),
            o.getShipPostalCode(),
            o.getShipCountry()),
        o.getItems().stream().map(OrderMapper::toItem).toList(),
        o.getUserId(),
        o.getEmail(),
        o.getPaymentMethod().name(),
        o.getPaymentStatus().name(),
        o.getSubtotalAmount(),
        o.getDiscountAmount(),
        o.getCouponCode(),
        o.getShippingAmount(),
        o.getTaxAmount(),
        o.getTaxLabel(),
        hasBreakdown
            ? new TaxBreakdownResponse(o.getCgstAmount(), o.getSgstAmount(), o.getIgstAmount())
            : null,
        o.getEstimatedDeliveryDate(),
        o.getTrackingNumber(),
        o.getCarrier(),
        o.getDeliveredAt(),
        o.isCancellable(),
        o.isReturnable(now, window),
        o.returnWindowEndsAt(window),
        o.getHistory().stream()
            .map(
                h ->
                    new TimelineEntryResponse(
                        h.getStatus().name(), h.getNote(), h.getActor().name(), h.getCreatedAt()))
            .toList(),
        null,
        o.getCoinsRedeemed(),
        o.getCoinsValue(),
        o.getWalletAmount(),
        o.isMembershipFreeShipping(),
        o.getShipments().stream().map(s -> toShipment(o, s, now, window)).toList(),
        o.isRiskHold(),
        o.getRiskScore(),
        parseReasons(o.getRiskReasons()),
        o.getExchangeAmount(),
        o.getExchangeShortfall(),
        o.getExchangeQuoteId());
  }

  /** Reasons are stored as a JSON string array written by Order#applyRisk. */
  static java.util.List<String> parseReasons(String json) {
    if (json == null || json.length() < 3) {
      return java.util.List.of();
    }
    java.util.List<String> out = new java.util.ArrayList<>();
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
    while (m.find()) {
      out.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
    }
    return out;
  }

  static OrderItemResponse toItem(OrderItem i) {
    return new OrderItemResponse(
        i.getProductId(),
        i.getSku(),
        i.getName(),
        i.getUnitPrice(),
        i.getQuantity(),
        i.getLineTotal(),
        i.getImageUrl(),
        i.getVariantId(),
        i.getVariantLabel(),
        i.getOfferId(),
        i.getSellerId(),
        i.getShipmentId());
  }

  public ShipmentResponse toShipment(Order o, OrderShipment s, Instant now, int window) {
    return new ShipmentResponse(
        s.getId(),
        s.getSellerId(),
        s.getSellerName(),
        s.getStatus().name(),
        s.getTrackingNumber(),
        s.getCarrier(),
        s.getEstimatedDeliveryDate(),
        s.getDeliveredAt(),
        s.getAmount(),
        s.getWarehouseCode(),
        o.isShipmentReturnable(s, now, window),
        itemsOf(o, s),
        timelineOf(o, s));
  }

  /** The seller's own view of a shipment (ship-to for fulfilment; no customer e-mail). */
  public SellerShipmentResponse toSellerView(OrderShipment s) {
    Order o = s.getOrder();
    return new SellerShipmentResponse(
        s.getId(),
        o.getId(),
        s.getStatus().name(),
        s.getTrackingNumber(),
        s.getCarrier(),
        s.getEstimatedDeliveryDate(),
        s.getDeliveredAt(),
        s.getAmount(),
        o.getCurrency(),
        o.getPlacedAt(),
        o.getPaymentMethod().name(),
        s.getWarehouseCode(),
        new ShipToResponse(
            o.getShipFullName(),
            o.getShipPhone(),
            o.getShipLine1(),
            o.getShipLine2(),
            o.getShipCity(),
            o.getShipState(),
            o.getShipPostalCode(),
            o.getShipCountry()),
        itemsOf(o, s),
        timelineOf(o, s));
  }

  private static List<OrderItemResponse> itemsOf(Order o, OrderShipment s) {
    return o.getItems().stream()
        .filter(i -> s.getId().equals(i.getShipmentId()))
        .map(OrderMapper::toItem)
        .toList();
  }

  private static List<TimelineEntryResponse> timelineOf(Order o, OrderShipment s) {
    List<TimelineEntryResponse> out = new ArrayList<>();
    o.getHistory().stream()
        .filter(h -> s.getId().equals(h.getShipmentId()))
        .forEach(
            h ->
                out.add(
                    new TimelineEntryResponse(
                        h.getStatus().name(), h.getNote(), h.getActor().name(), h.getCreatedAt())));
    if (out.isEmpty()
        || !(out.get(0).status().equals("PLACED") || out.get(0).status().equals("PENDING_PAYMENT"))) {
      out.add(0, new TimelineEntryResponse("PLACED", "Shipment created", "SYSTEM", s.getCreatedAt()));
    }
    if (s.getStatus() == OrderStatus.CANCELLED
        && !out.get(out.size() - 1).status().equals("CANCELLED")) {
      out.add(
          new TimelineEntryResponse(
              "CANCELLED",
              "Shipment cancelled",
              "SYSTEM",
              o.getCancelledAt() != null ? o.getCancelledAt() : s.getUpdatedAt()));
    }
    return out;
  }

  public ReturnResponse toResponse(OrderReturn r) {
    return new ReturnResponse(
        r.getId(),
        r.getOrder().getId(),
        r.getStatus().name(),
        r.getReason().name(),
        r.getComment(),
        r.getItems().stream()
            .map(
                i ->
                    new ReturnResponse.ReturnItemResponse(
                        i.getProductId(),
                        i.getVariantId(),
                        i.getName(),
                        i.getQuantity(),
                        i.getUnitPrice()))
            .toList(),
        r.getRefundAmount(),
        r.getCreatedAt(),
        r.getUpdatedAt(),
        r.getAdminNote(),
        r.getShipmentId(),
        r.isAbuseFlag());
  }
}
