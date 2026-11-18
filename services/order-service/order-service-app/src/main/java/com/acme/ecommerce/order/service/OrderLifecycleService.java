package com.acme.ecommerce.order.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.web.job.JobLock;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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

  /**
   * Wraps a state change and its follow-ups (outbox rows: releases, payment cancel, events) in one
   * transaction, so they commit together or not at all (TD-P6-01).
   */
  private final TransactionTemplate tx;

  public OrderLifecycleService(
      OrderStore store,
      OrderRemote remote,
      OrderEventPublisher events,
      OrderProperties props,
      TransactionTemplate tx) {
    this.store = store;
    this.remote = remote;
    this.events = events;
    this.props = props;
    this.tx = tx;
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
    final boolean refundedFinal = refunded;
    Result result =
        tx.execute(
            s -> {
              Result r = store.cancel(ownerOrNull, id, actor, note, refundedFinal);
              if (r.changed()) {
                afterCancel(r.order());
              }
              return r;
            });
    return result.order();
  }

  private void afterCancel(OrderResponse order) {
    // An unpaid card order (cancel, expiry or failed payment): stop its payment from being taken
    // later. Idempotent on the payment side, so the failed-payment path calling it is harmless.
    if ("CARD".equals(order.paymentMethod()) && "FAILED".equals(order.paymentStatus())) {
      remote.cancelPaymentBestEffort(order.id());
    }
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
    return tx.execute(
        s ->
            afterAdvance(
                store.advance(
                    id, target, update.note(), update.trackingNumber(), update.carrier())));
  }

  /** Admin: move one shipment of an order. */
  public OrderResponse adminAdvanceShipment(UUID orderId, UUID shipmentId, StatusUpdate update) {
    return tx.execute(
        s ->
            afterAdvance(
                store.advanceShipment(
                    orderId,
                    null,
                    shipmentId,
                    OrderStatus.valueOf(update.status().name()),
                    update.note(),
                    update.trackingNumber(),
                    update.carrier(),
                    HistoryActor.ADMIN)));
  }

  /** Seller: move one of their own shipments (anything else is a 404). */
  public OrderResponse sellerAdvanceShipment(UUID userId, UUID shipmentId, StatusUpdate update) {
    UUID sellerId = remote.approvedSellerId(userId);
    if (sellerId == null) {
      throw new CodedException(ErrorCode.FORBIDDEN, "An approved seller account is required");
    }
    String note =
        update.note() == null || update.note().isBlank() ? "Updated by seller" : update.note();
    return tx.execute(
        s ->
            afterAdvance(
                store.advanceShipment(
                    null,
                    sellerId,
                    shipmentId,
                    OrderStatus.valueOf(update.status().name()),
                    note,
                    update.trackingNumber(),
                    update.carrier(),
                    HistoryActor.SELLER)));
  }

  /**
   * COD collection on order DELIVERED, then SHIPMENT_STATUS_CHANGED (+ ORDER_STATUS_CHANGED if
   * derived status moved).
   */
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
      Result r =
          tx.execute(
              s -> {
                Result paid = store.markPaid(orderId, paymentId);
                if (paid.changed()) {
                  events.publish(OrderEventType.ORDER_PLACED, paid.order());
                }
                return paid;
              });
      if (!r.changed()
          && "CANCELLED".equals(r.order().status())
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
          log.error(
              "Order {} was paid after cancellation and the refund failed; payment-service will"
                  + " retry: {}",
              orderId,
              e.toString());
          // Not 200: payment-service then keeps the outcome unreported and re-sends it every 30 s,
          // and the refund is retried with the same idempotency key (TD-P6-03).
          throw new CodedException(
              ErrorCode.UPSTREAM_UNAVAILABLE, "Late payment refund failed; retry later", e);
        }
      }
      OrderResponse o = store.getAny(orderId);
      return new PaymentResultOutcome(orderId, o.status(), o.paymentStatus());
    }
    if ("FAILED".equalsIgnoreCase(outcome)) {
      Result r =
          tx.execute(
              s -> {
                Result failed = store.markPaymentFailed(orderId);
                if (failed.changed()) {
                  afterCancel(failed.order());
                }
                return failed;
              });
      return new PaymentResultOutcome(orderId, r.order().status(), r.order().paymentStatus());
    }
    throw new CodedException(ErrorCode.VALIDATION_FAILED, "Unknown payment outcome: " + outcome);
  }

  /** Cluster-wide guard so only one replica runs this job per tick. */
  @Autowired private JobLock jobLock;

  // Every 60s: PENDING_PAYMENT orders older than the expiry window are cancelled.

  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  public void expireStalePendingOrders() {
    jobLock.runExclusive("order-expire-pending", this::expireStalePendingOrdersLocked);
  }

  private void expireStalePendingOrdersLocked() {
    Instant cutoff = Instant.now().minus(props.getPending().getExpiry());
    List<UUID> ids = store.expiredPendingIds(cutoff);
    for (UUID id : ids) {
      try {
        tx.executeWithoutResult(
            s -> {
              Result r = store.systemCancel(id, "Payment not completed");
              if (r.changed()) {
                afterCancel(r.order());
              }
            });
      } catch (RuntimeException e) {
        log.warn("Could not expire order {}: {}", id, e.toString());
      }
    }
    if (!ids.isEmpty()) {
      log.info("Expired {} unpaid order(s)", ids.size());
    }
  }
}
