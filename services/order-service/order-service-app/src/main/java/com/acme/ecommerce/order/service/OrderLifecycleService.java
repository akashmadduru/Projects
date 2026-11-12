package com.acme.ecommerce.order.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.api.dto.AdminRequests.StatusUpdate;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.config.OrderProperties;
import com.acme.ecommerce.order.domain.HistoryActor;
import com.acme.ecommerce.order.domain.OrderStatus;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.event.OrderEventType;
import com.acme.ecommerce.order.service.OrderStore.CancelPlan;
import com.acme.ecommerce.order.service.OrderStore.Result;
import com.acme.ecommerce.order.service.OrderStore.ShipmentAdvance;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Status changes after placement: cancel, admin fulfilment steps, payment results and the expiry
 * job. Pattern: decide in a short DB transaction (OrderStore), then perform remote side effects
 * (refund first when money has to move, stock/coupon release and events afterwards).
 */
@Service
public class OrderLifecycleService {

  private static final Logger log = LoggerFactory.getLogger(OrderLifecycleService.class);

  public record PaymentResultOutcome(UUID orderId, String status, String paymentStatus) {}

  private final OrderStore store;
  private final OrderRemote remote;
  private final OrderEventPublisher events;
  private final OrderProperties props;

  public OrderLifecycleService(
      OrderStore store, OrderRemote remote, OrderEventPublisher events, OrderProperties props) {
    this.store = store;
    this.remote = remote;
    this.events = events;
    this.props = props;
  }

  public OrderResponse cancelByUser(UUID userId, UUID id) {
    return cancel(userId, id, HistoryActor.USER, "Cancelled by customer");
  }

  public OrderResponse cancelByAdmin(UUID id, String reason) {
    return cancel(null, id, HistoryActor.ADMIN, "Cancelled by admin: " + reason);
  }

  private OrderResponse cancel(UUID ownerOrNull, UUID id, HistoryActor actor, String note) {
    CancelPlan plan = store.cancelPlan(ownerOrNull, id);
    if (plan.alreadyCancelled()) {
      return ownerOrNull == null ? store.getAny(id) : store.get(ownerOrNull, id);
    }
    boolean refunded = false;
    if (plan.refundNeeded()) {
      // Money first: if payment-service is down the order stays as it was and the caller gets 503.
      if (plan.amount().signum() > 0) {
        remote.refund(id, plan.amount(), "Order cancelled", "cancel-" + id);
      }
      refunded = true;
    }
    Result result = store.cancel(ownerOrNull, id, actor, note, refunded);
    if (result.changed()) {
      afterCancel(result.order());
    }
    return result.order();
  }

  private void afterCancel(OrderResponse order) {
    remote.restoreStockBestEffort(order.id());
    if (order.couponCode() != null) {
      remote.releaseCouponBestEffort(order.id());
    }
    if (order.coinsRedeemed() > 0) {
      remote.releaseCoinsBestEffort(order.id());
    }
    if (order.walletAmount().signum() > 0) {
      remote.creditWalletBackBestEffort(
          order.userId(), order.walletAmount(), order.currency(), order.id());
    }
    events.publish(OrderEventType.ORDER_CANCELLED, order);
  }

  public OrderResponse adminAdvance(UUID id, StatusUpdate update) {
    OrderStatus target = OrderStatus.valueOf(update.status().name());
    ShipmentAdvance adv =
        store.advance(id, target, update.note(), update.trackingNumber(), update.carrier());
    return afterAdvance(adv);
  }

  /** Admin: move one shipment of an order. */
  public OrderResponse adminAdvanceShipment(UUID orderId, UUID shipmentId, StatusUpdate update) {
    return afterAdvance(
        store.advanceShipment(
            orderId,
            null,
            shipmentId,
            OrderStatus.valueOf(update.status().name()),
            update.note(),
            update.trackingNumber(),
            update.carrier(),
            HistoryActor.ADMIN));
  }

  /** Seller: move one of their own shipments (anything else is a 404). */
  public OrderResponse sellerAdvanceShipment(UUID userId, UUID shipmentId, StatusUpdate update) {
    UUID sellerId = remote.approvedSellerId(userId);
    if (sellerId == null) {
      throw new CodedException(ErrorCode.FORBIDDEN, "An approved seller account is required");
    }
    String note =
        update.note() == null || update.note().isBlank() ? "Updated by seller" : update.note();
    return afterAdvance(
        store.advanceShipment(
            null,
            sellerId,
            shipmentId,
            OrderStatus.valueOf(update.status().name()),
            note,
            update.trackingNumber(),
            update.carrier(),
            HistoryActor.SELLER));
  }

  /** COD collection on order DELIVERED, then SHIPMENT_STATUS_CHANGED (+ ORDER_STATUS_CHANGED if derived status moved). */
  private OrderResponse afterAdvance(ShipmentAdvance adv) {
    OrderResponse order = adv.order();
    boolean orderMoved = !order.status().equals(adv.previousOrderStatus());
    if (orderMoved && "DELIVERED".equals(order.status()) && "COD".equals(order.paymentMethod())) {
      remote.codCollectedBestEffort(order.id());
    }
    if (adv.shipment() != null) {
      events.publishShipment(OrderEventType.SHIPMENT_STATUS_CHANGED, order, adv.shipment());
    }
    if (orderMoved || adv.shipment() == null) {
      events.publish(OrderEventType.ORDER_STATUS_CHANGED, order);
    }
    return order;
  }

  /** Internal: payment-service reports the outcome. Idempotent. */
  public PaymentResultOutcome paymentResult(UUID orderId, String outcome, String paymentId) {
    if ("SUCCEEDED".equalsIgnoreCase(outcome)) {
      Result r = store.markPaid(orderId, paymentId);
      if (r.changed()) {
        events.publish(OrderEventType.ORDER_PLACED, r.order());
      } else if ("CANCELLED".equals(r.order().status())
          && !"REFUNDED".equals(r.order().paymentStatus())) {
        // Paid after the order was cancelled/expired: give the money back.
        try {
          remote.refund(
              orderId,
              r.order().totalAmount().subtract(r.order().walletAmount()),
              "Paid after cancellation",
              "late-success-" + orderId);
          store.markLateRefund(orderId);
        } catch (RuntimeException e) {
          log.error("Order {} was paid after cancellation and the refund failed: {}", orderId, e.toString());
        }
      }
      OrderResponse o = store.getAny(orderId);
      return new PaymentResultOutcome(orderId, o.status(), o.paymentStatus());
    }
    if ("FAILED".equalsIgnoreCase(outcome)) {
      Result r = store.markPaymentFailed(orderId);
      if (r.changed()) {
        afterCancel(r.order());
      }
      return new PaymentResultOutcome(orderId, r.order().status(), r.order().paymentStatus());
    }
    throw new CodedException(ErrorCode.VALIDATION_FAILED, "Unknown payment outcome: " + outcome);
  }

  /** Every 60s: PENDING_PAYMENT orders older than the expiry window are cancelled. */
  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  public void expireStalePendingOrders() {
    Instant cutoff = Instant.now().minus(props.getPending().getExpiry());
    List<UUID> ids = store.expiredPendingIds(cutoff);
    for (UUID id : ids) {
      try {
        Result r = store.systemCancel(id, "Payment not completed");
        if (r.changed()) {
          afterCancel(r.order());
        }
      } catch (RuntimeException e) {
        log.warn("Could not expire order {}: {}", id, e.toString());
      }
    }
    if (!ids.isEmpty()) {
      log.info("Expired {} unpaid order(s)", ids.size());
    }
  }
}
