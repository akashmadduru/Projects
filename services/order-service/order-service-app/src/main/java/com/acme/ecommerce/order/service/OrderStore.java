package com.acme.ecommerce.order.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.DomainRuleViolationException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.order.api.dto.AdminOrderSummary;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.api.dto.OrderSummaryResponse;
import com.acme.ecommerce.order.api.dto.PurchaseStatusResponse;
import com.acme.ecommerce.order.api.dto.ReturnResponse;
import com.acme.ecommerce.order.config.OrderProperties;
import com.acme.ecommerce.order.domain.HistoryActor;
import com.acme.ecommerce.order.domain.Order;
import com.acme.ecommerce.order.domain.Order.ReturnLine;
import com.acme.ecommerce.order.domain.OrderItem;
import com.acme.ecommerce.order.domain.OrderReturn;
import com.acme.ecommerce.order.domain.OrderReturnItem;
import com.acme.ecommerce.order.domain.OrderStatus;
import com.acme.ecommerce.order.domain.PaymentMethod;
import com.acme.ecommerce.order.domain.PaymentStatus;
import com.acme.ecommerce.order.domain.ReturnReason;
import com.acme.ecommerce.order.domain.ReturnStatus;
import com.acme.ecommerce.order.repository.OrderRepository;
import com.acme.ecommerce.order.repository.OrderReturnRepository;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional persistence boundary for orders and returns. Kept separate from the orchestration
 * services so remote calls never hold a database transaction open, and so a unique-constraint race
 * on the idempotency key surfaces OUTSIDE the failed transaction.
 */
@Service
public class OrderStore {

  /** A state change plus whether this call actually changed anything (idempotency signal). */
  public record Result(OrderResponse order, boolean changed) {}

  /** What a cancel needs to know up front. */
  public record CancelPlan(
      boolean alreadyCancelled, boolean refundNeeded, BigDecimal amount, String currency) {}

  /** What a return refund needs to know up front. */
  public record ReturnRefundPlan(
      UUID returnId,
      UUID orderId,
      BigDecimal refundAmount,
      String currency,
      boolean card,
      boolean paid,
      List<ReturnLine> lines) {}

  private final OrderRepository orders;
  private final OrderReturnRepository returns;
  private final OrderMapper mapper;
  private final OrderProperties props;

  public OrderStore(
      OrderRepository orders,
      OrderReturnRepository returns,
      OrderMapper mapper,
      OrderProperties props) {
    this.orders = orders;
    this.returns = returns;
    this.mapper = mapper;
    this.props = props;
  }

  // ---- placement ---------------------------------------------------------------------------

  /** The order previously placed with this key, if any; a different address is a 422. */
  @Transactional(readOnly = true)
  public Optional<OrderResponse> findReplay(UUID userId, String key, UUID addressId) {
    return orders
        .findByUserIdAndIdempotencyKey(userId, key)
        .map(
            existing -> {
              if (!existing.getAddressId().equals(addressId)) {
                throw new DomainRuleViolationException(
                    "Idempotency-Key was already used with a different request",
                    "IDEMPOTENCY_KEY_REUSED");
              }
              return mapper.toResponse(existing);
            });
  }

  @Transactional
  public OrderResponse save(Order order) {
    return mapper.toResponse(orders.saveAndFlush(order));
  }

  @Transactional
  public void attachPayment(UUID orderId, String paymentId) {
    orders.findById(orderId).ifPresent(o -> o.attachPayment(paymentId));
  }

  /** The user has no previous non-cancelled order (drives first-order-only coupons). */
  @Transactional(readOnly = true)
  public boolean isFirstOrder(UUID userId) {
    return orders.countByUserIdAndStatusNot(userId, OrderStatus.CANCELLED) == 0;
  }

  // ---- reads -------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public OrderResponse get(UUID userId, UUID id) {
    return mapper.toResponse(find(userId, id));
  }

  @Transactional(readOnly = true)
  public OrderResponse getAny(UUID id) {
    return mapper.toResponse(findAny(id));
  }

  @Transactional(readOnly = true)
  public Page<OrderSummaryResponse> list(UUID userId, Pageable pageable) {
    return orders.findSummaries(userId, pageable);
  }

  /** Whether the user has a paid (non-cancelled, non-pending) order containing the product. */
  @Transactional(readOnly = true)
  public PurchaseStatusResponse purchaseStatus(UUID userId, UUID productId) {
    return orders
        .findContainingProduct(
            userId,
            productId,
            List.of(OrderStatus.CANCELLED, OrderStatus.PENDING_PAYMENT),
            PageRequest.of(0, 1))
        .stream()
        .findFirst()
        .map(o -> new PurchaseStatusResponse(productId, true, o.getId(), o.getPlacedAt()))
        .orElseGet(() -> new PurchaseStatusResponse(productId, false, null, null));
  }

  @Transactional(readOnly = true)
  public Page<AdminOrderSummary> adminList(OrderStatus status, String term, Pageable pageable) {
    String q = term == null ? "" : term.trim().toLowerCase().replaceAll("[%_\\\\]", "");
    Specification<Order> spec =
        (root, query, cb) -> {
          List<Predicate> ps = new ArrayList<>();
          if (status != null) {
            ps.add(cb.equal(root.get("status"), status));
          }
          if (!q.isEmpty()) {
            ps.add(
                cb.or(
                    cb.like(cb.lower(root.get("email")), "%" + q + "%"),
                    cb.like(cb.function("text", String.class, root.get("id")), q + "%")));
          }
          return cb.and(ps.toArray(new Predicate[0]));
        };
    return orders
        .findAll(spec, pageable)
        .map(
            o ->
                new AdminOrderSummary(
                    o.getId(),
                    o.getStatus().name(),
                    o.getPaymentMethod().name(),
                    o.getPaymentStatus().name(),
                    o.getEmail(),
                    o.getTotalAmount(),
                    o.getCurrency(),
                    o.getItems().stream().mapToLong(OrderItem::getQuantity).sum(),
                    o.getPlacedAt()));
  }

  @Transactional(readOnly = true)
  public List<UUID> expiredPendingIds(Instant cutoff) {
    return orders.findByStatusAndPlacedAtBefore(OrderStatus.PENDING_PAYMENT, cutoff).stream()
        .map(Order::getId)
        .toList();
  }

  // ---- lifecycle ---------------------------------------------------------------------------

  @Transactional
  public Result markPaid(UUID orderId, String paymentId) {
    Order o = findAny(orderId);
    boolean changed = o.markPaid(paymentId, Instant.now());
    return new Result(mapper.toResponse(o), changed);
  }

  @Transactional
  public Result markPaymentFailed(UUID orderId) {
    Order o = findAny(orderId);
    boolean changed = o.markPaymentFailed(Instant.now());
    return new Result(mapper.toResponse(o), changed);
  }

  /** Validates cancellability and tells the caller whether a refund must happen first. */
  @Transactional(readOnly = true)
  public CancelPlan cancelPlan(UUID ownerOrNull, UUID id) {
    Order o = ownerOrNull == null ? findAny(id) : find(ownerOrNull, id);
    if (o.getStatus() == OrderStatus.CANCELLED) {
      return new CancelPlan(true, false, BigDecimal.ZERO, o.getCurrency());
    }
    if (!o.isCancellable()) {
      throw new CodedException(
          ErrorCode.ORDER_NOT_CANCELLABLE,
          "Order cannot be cancelled in status " + o.getStatus());
    }
    boolean refund = o.getPaymentStatus() == PaymentStatus.PAID && o.getPaymentId() != null;
    return new CancelPlan(false, refund, o.getTotalAmount(), o.getCurrency());
  }

  @Transactional
  public Result cancel(
      UUID ownerOrNull, UUID id, HistoryActor actor, String note, boolean refunded) {
    Order o = ownerOrNull == null ? findAny(id) : find(ownerOrNull, id);
    boolean changed = o.cancel(actor, note, Instant.now());
    if (changed && refunded) {
      o.recordRefund(o.getTotalAmount(), true);
    }
    return new Result(mapper.toResponse(o), changed);
  }

  /** Cancels a PENDING order whose payment can never complete (system actor). */
  @Transactional
  public Result systemCancel(UUID id, String note) {
    Order o = findAny(id);
    boolean changed = o.cancel(HistoryActor.SYSTEM, note, Instant.now());
    return new Result(mapper.toResponse(o), changed);
  }

  @Transactional
  public OrderResponse advance(
      UUID id, OrderStatus target, String note, String tracking, String carrier) {
    Order o = findAny(id);
    if (target == OrderStatus.SHIPPED && (tracking == null || tracking.isBlank())) {
      throw new DomainRuleViolationException("trackingNumber is required when shipping");
    }
    o.advance(target, note, tracking, carrier, Instant.now());
    return mapper.toResponse(o);
  }

  /** Marks a late-succeeded payment on an already cancelled order as refunded. */
  @Transactional
  public void markLateRefund(UUID id) {
    Order o = findAny(id);
    o.recordLateRefund(Instant.now());
  }

  // ---- returns -----------------------------------------------------------------------------

  public record ReturnOutcome(ReturnResponse ret, OrderResponse order) {}

  @Transactional
  public ReturnOutcome requestReturn(
      UUID userId,
      UUID orderId,
      List<ReturnLine> lines,
      ReturnReason reason,
      String comment) {
    Order o = find(userId, orderId);
    OrderReturn r =
        o.requestReturn(
            lines, reason, comment, props.getReturns().getWindowDays(), Instant.now());
    orders.flush();
    return new ReturnOutcome(mapper.toResponse(r), mapper.toResponse(o));
  }

  @Transactional(readOnly = true)
  public List<ReturnResponse> listReturns(UUID userId, UUID orderId) {
    return find(userId, orderId).getReturns().stream().map(mapper::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public Page<ReturnResponse> adminReturns(ReturnStatus status, Pageable pageable) {
    Page<OrderReturn> page =
        status == null ? returns.findAll(pageable) : returns.findByStatus(status, pageable);
    return page.map(mapper::toResponse);
  }

  @Transactional
  public ReturnResponse decide(UUID returnId, boolean approve, String note) {
    OrderReturn r = findReturn(returnId);
    r.decide(approve, note, Instant.now());
    return mapper.toResponse(r);
  }

  @Transactional
  public ReturnResponse pickedUp(UUID returnId) {
    OrderReturn r = findReturn(returnId);
    r.markPickedUp(Instant.now());
    return mapper.toResponse(r);
  }

  /** Validates the return is ready to be refunded and describes the refund. */
  @Transactional(readOnly = true)
  public ReturnRefundPlan refundPlan(UUID returnId) {
    OrderReturn r = findReturn(returnId);
    if (r.getStatus() != ReturnStatus.PICKED_UP) {
      throw new CodedException(
          ErrorCode.INVALID_STATUS_TRANSITION,
          "Return is " + r.getStatus() + ", expected PICKED_UP");
    }
    Order o = r.getOrder();
    return new ReturnRefundPlan(
        r.getId(),
        o.getId(),
        r.getRefundAmount(),
        o.getCurrency(),
        o.getPaymentMethod() == PaymentMethod.CARD && o.getPaymentId() != null,
        o.getPaymentStatus() == PaymentStatus.PAID
            || o.getPaymentStatus() == PaymentStatus.PARTIALLY_REFUNDED,
        r.getItems().stream()
            .map((OrderReturnItem i) -> new ReturnLine(i.getProductId(), i.getQuantity()))
            .toList());
  }

  @Transactional
  public ReturnOutcome completeRefund(UUID returnId) {
    OrderReturn r = findReturn(returnId);
    r.markRefunded(Instant.now());
    Order o = r.getOrder();
    BigDecimal itemsValue = o.getSubtotalAmount().subtract(o.getDiscountAmount());
    boolean full = o.getRefundedAmount().add(r.getRefundAmount()).compareTo(itemsValue) >= 0;
    o.recordRefund(r.getRefundAmount(), full);
    return new ReturnOutcome(mapper.toResponse(r), mapper.toResponse(o));
  }

  // ---- lookups -----------------------------------------------------------------------------

  private Order find(UUID userId, UUID id) {
    return orders
        .findByIdAndUserId(id, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Order", id));
  }

  private Order findAny(UUID id) {
    return orders.findById(id).orElseThrow(() -> new ResourceNotFoundException("Order", id));
  }

  private OrderReturn findReturn(UUID id) {
    return returns.findById(id).orElseThrow(() -> new ResourceNotFoundException("Return", id));
  }
}
