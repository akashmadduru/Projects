package com.acme.ecommerce.order.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.api.dto.CreateReturnRequest;
import com.acme.ecommerce.order.api.dto.ReturnResponse;
import com.acme.ecommerce.order.domain.Order.ReturnLine;
import com.acme.ecommerce.order.domain.ReturnReason;
import com.acme.ecommerce.order.domain.ReturnStatus;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.event.OrderEventType;
import com.acme.ecommerce.order.service.OrderStore.ReturnOutcome;
import com.acme.ecommerce.order.service.OrderStore.ReturnRefundPlan;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Returns: request (customer) and the admin decision / pickup / refund steps. */
@Service
public class ReturnService {

  private static final Logger log = LoggerFactory.getLogger(ReturnService.class);

  private final OrderStore store;
  private final OrderRemote remote;
  private final OrderEventPublisher events;
  private final TransactionTemplate tx;

  public ReturnService(
      OrderStore store, OrderRemote remote, OrderEventPublisher events, TransactionTemplate tx) {
    this.store = store;
    this.remote = remote;
    this.events = events;
    this.tx = tx;
  }

  public ReturnResponse request(UUID userId, UUID orderId, CreateReturnRequest req) {
    List<ReturnLine> lines =
        req.items().stream()
            .map(i -> new ReturnLine(i.productId(), i.variantId(), i.quantity()))
            .toList();
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
   * Refund, admin only. Wallet leg first, committed as done before the rest leg; the rest leg goes
   * to payment-service under a key that stays the same across retries, so a retry never pays twice.
   * REFUNDED only after the money has settled: a PENDING card leg leaves the return PICKED_UP until
   * {@link #resumePending} sees it settle. Stock release and the event are written with the status
   * change in one transaction.
   */
  public ReturnResponse refund(UUID returnId) {
    if (store.refundPlan(returnId).status() == ReturnStatus.REFUNDED) {
      return store.view(returnId);
    }
    Instant lease = store.claimRefundLease(returnId);
    if (lease == null) {
      if (store.refundPlan(returnId).status() == ReturnStatus.REFUNDED) {
        return store.view(returnId);
      }
      throw new CodedException(ErrorCode.RESOURCE_CONFLICT, "Refund in progress");
    }
    try {
      return runLegs(store.freezeLegs(returnId));
    } finally {
      store.releaseRefundLease(returnId, lease);
    }
  }

  private ReturnResponse runLegs(ReturnRefundPlan plan) {
    UUID id = plan.returnId();
    if (plan.wallet().signum() > 0 && !plan.walletDone()) {
      remote.creditWallet(
          plan.userId(), plan.wallet(), plan.currency(), plan.orderId(), "return-wallet-" + id);
      store.markWalletLegDone(id);
    }
    String state = plan.restState();
    if (!"NONE".equals(plan.route()) && !"SUCCEEDED".equals(state)) {
      state = restLeg(plan);
    }
    if ("PENDING".equals(state)) {
      return store.view(id);
    }
    return finalizeRefund(plan);
  }

  /**
   * Runs the rest leg and records its state; an exception records FAILED and never bumps the key.
   */
  private String restLeg(ReturnRefundPlan plan) {
    UUID id = plan.returnId();
    String state;
    if ("DIRECT_WALLET".equals(plan.route())) {
      state =
          guarded(
              id,
              () -> {
                remote.creditWallet(
                    plan.userId(),
                    plan.rest(),
                    plan.currency(),
                    plan.orderId(),
                    "return-rest-" + id);
                return "SUCCEEDED";
              });
    } else {
      state = guarded(id, () -> callRefund(plan, plan.key()));
      if ("FAILED".equals(state)) {
        // Only a FAILED refund row may start a new key; the loser of a race gets 409.
        if (!store.bumpRestLegAttempt(id, plan.attempt())) {
          throw new CodedException(ErrorCode.RESOURCE_CONFLICT, "Refund in progress");
        }
        state = guarded(id, () -> callRefund(plan, "return-" + id + "-r" + (plan.attempt() + 1)));
      }
    }
    store.setRestLegState(id, state);
    return state;
  }

  private String guarded(UUID id, Supplier<String> call) {
    try {
      return call.get();
    } catch (RuntimeException e) {
      store.setRestLegState(id, "FAILED");
      throw e;
    }
  }

  private String callRefund(ReturnRefundPlan plan, String key) {
    return remote.refund(plan.orderId(), plan.rest(), "Return refunded", key, plan.destination());
  }

  private ReturnResponse finalizeRefund(ReturnRefundPlan plan) {
    return tx.execute(
        status -> {
          ReturnOutcome outcome = store.completeRefund(plan.returnId());
          remote.releaseStock(plan.orderId(), plan.returnId(), plan.lines());
          events.publishReturn(OrderEventType.RETURN_REFUNDED, outcome.order(), outcome.ret());
          return outcome.ret();
        });
  }

  /**
   * Settles a PENDING card leg: replays the same key (payment-service answers from its own table,
   * no second refund). SUCCEEDED finalizes; FAILED is recorded and the return stays PICKED_UP for
   * an admin retry; PENDING does nothing.
   */
  public void resumePending(UUID returnId) {
    Instant lease = store.claimRefundLease(returnId);
    if (lease == null) {
      return;
    }
    try {
      ReturnRefundPlan plan = store.refundPlan(returnId);
      if (plan.status() != ReturnStatus.PICKED_UP || !"PENDING".equals(plan.restState())) {
        return;
      }
      String state = callRefund(plan, plan.key());
      if ("SUCCEEDED".equals(state)) {
        store.setRestLegState(returnId, state);
        finalizeRefund(plan);
      } else if ("FAILED".equals(state)) {
        store.setRestLegState(returnId, state);
        log.error(
            "RETURN REFUND FAILED: return {} of order {} was rejected by the card network;"
                + " retry it from the admin",
            returnId,
            plan.orderId());
      } else {
        // Still PENDING: touch updated_at so the oldest-first 50-row window rotates to others.
        store.setRestLegState(returnId, "PENDING");
      }
    } finally {
      store.releaseRefundLease(returnId, lease);
    }
  }
}
