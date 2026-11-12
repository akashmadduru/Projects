package com.acme.ecommerce.order.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.DomainRuleViolationException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.order.api.dto.AdminOrderStats;
import com.acme.ecommerce.order.api.dto.AdminOrderSummary;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.api.dto.OrderSummaryResponse;
import com.acme.ecommerce.order.api.dto.PurchaseStatusResponse;
import com.acme.ecommerce.order.api.dto.ReturnResponse;
import com.acme.ecommerce.order.api.dto.SellerShipmentResponse;
import com.acme.ecommerce.order.api.dto.ShipmentResponse;
import com.acme.ecommerce.order.config.OrderProperties;
import com.acme.ecommerce.order.domain.HistoryActor;
import com.acme.ecommerce.order.domain.Order;
import com.acme.ecommerce.order.domain.Order.ReturnLine;
import com.acme.ecommerce.order.domain.OrderItem;
import com.acme.ecommerce.order.domain.OrderReturn;
import com.acme.ecommerce.order.domain.OrderReturnItem;
import com.acme.ecommerce.order.domain.OrderShipment;
import com.acme.ecommerce.order.domain.OrderStatus;
import com.acme.ecommerce.order.domain.PaymentMethod;
import com.acme.ecommerce.order.domain.PaymentStatus;
import com.acme.ecommerce.order.domain.ReturnReason;
import com.acme.ecommerce.order.domain.ReturnStatus;
import com.acme.ecommerce.order.repository.OrderRepository;
import com.acme.ecommerce.order.repository.OrderReturnRepository;
import com.acme.ecommerce.order.repository.OrderShipmentRepository;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
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

  /** An advanced shipment (null for legacy orders without shipments) plus the order status before. */
  public record ShipmentAdvance(
      OrderResponse order, ShipmentResponse shipment, String previousOrderStatus) {}

  /** Who owns a shipment (internal lookup for seller-service). */
  public record ShipmentRef(
      UUID shipmentId, UUID orderId, UUID userId, UUID sellerId, String status) {}

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
      List<ReturnLine> lines,
      UUID userId,
      BigDecimal orderTotal,
      BigDecimal walletAmount,
      String method,
      String refundTo) {}

  private final OrderRepository orders;
  private final OrderReturnRepository returns;
  private final OrderShipmentRepository shipments;
  private final OrderMapper mapper;
  private final OrderProperties props;

  public OrderStore(
      OrderRepository orders,
      OrderReturnRepository returns,
      OrderShipmentRepository shipments,
      OrderMapper mapper,
      OrderProperties props) {
    this.orders = orders;
    this.returns = returns;
    this.shipments = shipments;
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

  /** Splits the lines into shipments by seller/warehouse from the reservation result. */
  @Transactional
  public OrderResponse assignShipments(
      UUID orderId,
      List<Order.Allocation> allocations,
      Map<UUID, String> sellerNames,
      Function<String, LocalDate> etaFor) {
    Order o = findAny(orderId);
    o.assignShipments(allocations, sellerNames, etaFor, Instant.now());
    orders.flush();
    return mapper.toResponse(o);
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
    return adminList(status, term, null, pageable);
  }

  @Transactional(readOnly = true)
  public Page<AdminOrderSummary> adminList(
      OrderStatus status, String term, UUID userId, Pageable pageable) {
    String q = term == null ? "" : term.trim().toLowerCase().replaceAll("[%_\\\\]", "");
    Specification<Order> spec =
        (root, query, cb) -> {
          List<Predicate> ps = new ArrayList<>();
          if (status != null) {
            ps.add(cb.equal(root.get("status"), status));
          }
          if (userId != null) {
            ps.add(cb.equal(root.get("userId"), userId));
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

  /** Dashboard aggregates: three grouped queries, no row materialisation. */
  @Transactional(readOnly = true)
  public AdminOrderStats adminStats(Instant now) {
    Map<String, Long> byStatus = new LinkedHashMap<>();
    for (OrderStatus s : OrderStatus.values()) {
      byStatus.put(s.name(), 0L);
    }
    for (Object[] row : orders.countGroupedByStatus()) {
      byStatus.put(((OrderStatus) row[0]).name(), (Long) row[1]);
    }
    Map<String, Long> byReturnStatus = new LinkedHashMap<>();
    for (ReturnStatus s : ReturnStatus.values()) {
      byReturnStatus.put(s.name(), 0L);
    }
    for (Object[] row : returns.countGroupedByStatus()) {
      byReturnStatus.put(((ReturnStatus) row[0]).name(), (Long) row[1]);
    }
    List<OrderStatus> excluded = List.of(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED);
    Instant startOfToday = now.truncatedTo(ChronoUnit.DAYS);
    return new AdminOrderStats(
        now,
        byStatus,
        byReturnStatus,
        revenue(orders.revenueSince(startOfToday, excluded)),
        revenue(orders.revenueSince(now.minus(30, ChronoUnit.DAYS), excluded)));
  }

  private static List<AdminOrderStats.CurrencyRevenue> revenue(List<Object[]> rows) {
    return rows.stream()
        .map(
            r ->
                new AdminOrderStats.CurrencyRevenue(
                    (String) r[0],
                    toBigDecimal(r[1]).subtract(toBigDecimal(r[2])),
                    ((Number) r[3]).longValue()))
        .toList();
  }

  private static BigDecimal toBigDecimal(Object value) {
    if (value instanceof BigDecimal bd) {
      return bd;
    }
    return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
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
    boolean refund =
        o.getPaymentStatus() == PaymentStatus.PAID
            && (o.getPaymentId() != null || o.getWalletAmount().signum() > 0);
    return new CancelPlan(
        false, refund, o.getTotalAmount().subtract(o.getWalletAmount()), o.getCurrency());
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

  /** Order-level admin move: applies to the order's only shipment (409 with several). */
  @Transactional
  public ShipmentAdvance advance(
      UUID id, OrderStatus target, String note, String tracking, String carrier) {
    Order o = findAny(id);
    if (target == OrderStatus.SHIPPED
        && (tracking == null || tracking.isBlank())
        && !hasTracking(o)) {
      throw new DomainRuleViolationException("trackingNumber is required when shipping");
    }
    String previous = o.getStatus().name();
    Instant now = Instant.now();
    o.advance(target, note, tracking, carrier, now);
    orders.flush();
    OrderShipment only = o.getShipments().size() == 1 ? o.getShipments().get(0) : null;
    return new ShipmentAdvance(
        mapper.toResponse(o),
        only == null ? null : mapper.toShipment(o, only, now, props.getReturns().getWindowDays()),
        previous);
  }

  private static boolean hasTracking(Order o) {
    if (o.getShipments().size() == 1) {
      String t = o.getShipments().get(0).getTrackingNumber();
      return t != null && !t.isBlank();
    }
    return o.getTrackingNumber() != null && !o.getTrackingNumber().isBlank();
  }

  /**
   * Moves one shipment. {@code sellerOrNull}: seller callers may only touch their own shipments
   * (anything else is a 404); {@code orderOrNull} pins the shipment to an order (admin route).
   */
  @Transactional
  public ShipmentAdvance advanceShipment(
      UUID orderOrNull,
      UUID sellerOrNull,
      UUID shipmentId,
      OrderStatus target,
      String note,
      String tracking,
      String carrier,
      HistoryActor actor) {
    OrderShipment s = findShipment(shipmentId);
    Order o = s.getOrder();
    if ((sellerOrNull != null && !sellerOrNull.equals(s.getSellerId()))
        || (orderOrNull != null && !orderOrNull.equals(o.getId()))) {
      throw new ResourceNotFoundException("Shipment", shipmentId);
    }
    if (target == OrderStatus.SHIPPED
        && (tracking == null || tracking.isBlank())
        && (s.getTrackingNumber() == null || s.getTrackingNumber().isBlank())) {
      throw new DomainRuleViolationException("trackingNumber is required when shipping");
    }
    String previous = o.getStatus().name();
    Instant now = Instant.now();
    o.advanceShipment(shipmentId, target, note, tracking, carrier, actor, now);
    orders.flush();
    return new ShipmentAdvance(
        mapper.toResponse(o),
        mapper.toShipment(o, s, now, props.getReturns().getWindowDays()),
        previous);
  }

  /** The seller's shipments, newest first; PENDING_PAYMENT ones are never shown. */
  @Transactional(readOnly = true)
  public Page<SellerShipmentResponse> sellerShipments(
      UUID sellerId, OrderStatus status, Pageable pageable) {
    Page<OrderShipment> page =
        status == null
            ? shipments.findBySellerIdAndStatusNot(sellerId, OrderStatus.PENDING_PAYMENT, pageable)
            : shipments.findBySellerIdAndStatus(sellerId, status, pageable);
    return page.map(mapper::toSellerView);
  }

  @Transactional(readOnly = true)
  public SellerShipmentResponse sellerShipment(UUID sellerId, UUID shipmentId) {
    OrderShipment s = findShipment(shipmentId);
    if (!sellerId.equals(s.getSellerId())) {
      throw new ResourceNotFoundException("Shipment", shipmentId);
    }
    return mapper.toSellerView(s);
  }

  @Transactional(readOnly = true)
  public ShipmentRef shipmentRef(UUID shipmentId) {
    OrderShipment s = findShipment(shipmentId);
    return new ShipmentRef(
        s.getId(), s.getOrder().getId(), s.getOrder().getUserId(), s.getSellerId(), s.getStatus().name());
  }

  private OrderShipment findShipment(UUID id) {
    return shipments.findById(id).orElseThrow(() -> new ResourceNotFoundException("Shipment", id));
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
      String comment,
      String refundTo,
      UUID shipmentId) {
    Order o = find(userId, orderId);
    OrderReturn r =
        o.requestReturn(
            lines,
            reason,
            comment,
            refundTo,
            shipmentId,
            props.getReturns().getWindowDays(),
            Instant.now());
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

  /** The offer a returned line was bought on (so inventory restocks that seller's warehouse). */
  private static UUID offerOf(Order o, UUID shipmentId, OrderReturnItem ri) {
    return o.getItems().stream()
        .filter(
            oi ->
                oi.getProductId().equals(ri.getProductId())
                    && java.util.Objects.equals(oi.getVariantId(), ri.getVariantId())
                    && (shipmentId == null || shipmentId.equals(oi.getShipmentId())))
        .map(oi -> oi.getOfferId())
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);
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
            || o.getPaymentStatus() == PaymentStatus.COD_COLLECTED
            || o.getPaymentStatus() == PaymentStatus.PARTIALLY_REFUNDED,
        r.getItems().stream()
            .map(
                (OrderReturnItem i) ->
                    new ReturnLine(
                        i.getProductId(),
                        i.getVariantId(),
                        i.getQuantity(),
                        offerOf(o, r.getShipmentId(), i)))
            .toList(),
        o.getUserId(),
        o.getTotalAmount(),
        o.getWalletAmount(),
        o.getPaymentMethod().name(),
        r.getRefundTo());
  }

  @Transactional
  public ReturnOutcome completeRefund(UUID returnId) {
    OrderReturn r = findReturn(returnId);
    r.markRefunded(Instant.now());
    Order o = r.getOrder();
    BigDecimal itemsValue = o.paidItemsValue();
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
