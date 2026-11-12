package com.acme.ecommerce.order.service;

import com.acme.ecommerce.order.api.dto.CreateReturnRequest;
import com.acme.ecommerce.order.api.dto.ReturnResponse;
import com.acme.ecommerce.order.domain.Order.ReturnLine;
import com.acme.ecommerce.order.domain.ReturnReason;
import com.acme.ecommerce.order.domain.ReturnStatus;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.event.OrderEventType;
import com.acme.ecommerce.order.service.OrderStore.ReturnOutcome;
import com.acme.ecommerce.order.service.OrderStore.ReturnRefundPlan;
import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/** Returns: request (customer) and the admin decision / pickup / refund steps. */
@Service
public class ReturnService {

  private final OrderStore store;
  private final OrderRemote remote;
  private final OrderEventPublisher events;

  public ReturnService(OrderStore store, OrderRemote remote, OrderEventPublisher events) {
    this.store = store;
    this.remote = remote;
    this.events = events;
  }

  public ReturnResponse request(UUID userId, UUID orderId, CreateReturnRequest req) {
    List<ReturnLine> lines =
        req.items().stream().map(i -> new ReturnLine(i.productId(), i.variantId(), i.quantity())).toList();
    ReturnOutcome outcome =
        store.requestReturn(
            userId,
            orderId,
            lines,
            ReturnReason.valueOf(req.reason().name()),
            req.comment() == null || req.comment().isBlank() ? null : req.comment().trim(),
            req.refundTo() == null ? "SOURCE" : req.refundTo().name(),
            req.shipmentId());
    events.publishReturn(OrderEventType.RETURN_REQUESTED, outcome.order(), outcome.ret());
    return outcome.ret();
  }

  public List<ReturnResponse> list(UUID userId, UUID orderId) {
    return store.listReturns(userId, orderId);
  }

  public Page<ReturnResponse> adminList(ReturnStatus status, Pageable pageable) {
    return store.adminReturns(status, pageable);
  }

  public ReturnResponse decide(UUID returnId, boolean approve, String note) {
    return store.decide(returnId, approve, note);
  }

  public ReturnResponse pickedUp(UUID returnId) {
    return store.pickedUp(returnId);
  }

  /**
   * Refund: for card payments the money moves first (503 if payment-service is down, return state
   * untouched), then the return is marked refunded, stock is restocked and the event published.
   */
  public ReturnResponse refund(UUID returnId) {
    ReturnRefundPlan plan = store.refundPlan(returnId);
    moveMoney(plan, returnId);
    ReturnOutcome outcome = store.completeRefund(returnId);
    remote.releaseStock(plan.orderId(), plan.returnId(), plan.lines());
    events.publishReturn(OrderEventType.RETURN_REFUNDED, outcome.order(), outcome.ret());
    return outcome.ret();
  }

  /**
   * Pro-rata split: the wallet share of the order goes back to the wallet, the rest to the card
   * source (or to the wallet when the customer asked for it, and always for cash on delivery).
   */
  private void moveMoney(ReturnRefundPlan plan, UUID returnId) {
    BigDecimal refund = plan.refundAmount();
    BigDecimal walletShare = BigDecimal.ZERO;
    if (plan.walletAmount().signum() > 0 && plan.orderTotal().signum() > 0) {
      walletShare =
          refund
              .multiply(plan.walletAmount())
              .divide(plan.orderTotal(), 2, RoundingMode.HALF_UP)
              .min(refund);
    }
    BigDecimal rest = refund.subtract(walletShare);
    if (walletShare.signum() > 0) {
      remote.creditWallet(
          plan.userId(), walletShare, plan.currency(), plan.orderId(), "return-wallet-" + returnId);
    }
    if (rest.signum() <= 0 || !plan.paid() || "WALLET".equals(plan.method())) {
      if (rest.signum() > 0 && "WALLET".equals(plan.method())) {
        remote.creditWallet(
            plan.userId(), rest, plan.currency(), plan.orderId(), "return-rest-" + returnId);
      }
      return;
    }
    String key = "return-" + returnId;
    if (plan.card()) {
      remote.refund(
          plan.orderId(),
          rest,
          "Return refunded",
          key,
          "WALLET".equals(plan.refundTo()) ? "WALLET" : null);
      return;
    }
    // Cash on delivery: the refund lands in the wallet.
    try {
      remote.refund(plan.orderId(), rest, "Return refunded", key, "WALLET");
    } catch (CodedException e) {
      if (e.errorCode() == ErrorCode.DEPENDENCY_UNAVAILABLE) {
        throw e;
      }
      remote.creditWallet(plan.userId(), rest, plan.currency(), plan.orderId(), "return-rest-" + returnId);
    }
  }
}
