package com.acme.ecommerce.order.service;

import com.acme.ecommerce.order.api.dto.OrderItemResponse;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.api.dto.ReturnResponse;
import com.acme.ecommerce.order.api.dto.ShipToResponse;
import com.acme.ecommerce.order.api.dto.TaxBreakdownResponse;
import com.acme.ecommerce.order.api.dto.TimelineEntryResponse;
import com.acme.ecommerce.order.config.OrderProperties;
import com.acme.ecommerce.order.domain.Order;
import com.acme.ecommerce.order.domain.OrderReturn;
import java.time.Instant;
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
        o.getItems().stream()
            .map(
                i ->
                    new OrderItemResponse(
                        i.getProductId(),
                        i.getSku(),
                        i.getName(),
                        i.getUnitPrice(),
                        i.getQuantity(),
                        i.getLineTotal(),
                        i.getImageUrl()))
            .toList(),
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
        null);
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
                        i.getProductId(), i.getName(), i.getQuantity(), i.getUnitPrice()))
            .toList(),
        r.getRefundAmount(),
        r.getCreatedAt(),
        r.getUpdatedAt(),
        r.getAdminNote());
  }
}
