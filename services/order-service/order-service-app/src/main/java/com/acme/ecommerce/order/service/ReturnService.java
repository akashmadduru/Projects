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
        req.items().stream().map(i -> new ReturnLine(i.productId(), i.quantity())).toList();
    ReturnOutcome outcome =
        store.requestReturn(
            userId,
            orderId,
            lines,
            ReturnReason.valueOf(req.reason().name()),
            req.comment() == null || req.comment().isBlank() ? null : req.comment().trim());
    events.publish(OrderEventType.RETURN_REQUESTED, outcome.order());
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
    if (plan.card() && plan.paid()) {
      remote.refund(
          plan.orderId(), plan.refundAmount(), "Return refunded", "return-" + plan.returnId());
    }
    ReturnOutcome outcome = store.completeRefund(returnId);
    remote.releaseStock(plan.orderId(), plan.returnId(), plan.lines());
    events.publish(OrderEventType.RETURN_REFUNDED, outcome.order());
    return outcome.ret();
  }
}
