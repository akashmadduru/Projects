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
import java.math.RoundingMode;
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

  /**
   * An advanced shipment (null for legacy orders without shipments) plus the order status before.
   */
  public record ShipmentAdvance(
      OrderResponse order, ShipmentResponse shipment, String previousOrderStatus) {}

  /** Who owns a shipment (internal lookup for seller-service). */
  public record ShipmentRef(
      UUID shipmentId, UUID orderId, UUID userId, UUID sellerId, String status) {}

  /** What a cancel needs to know up front. */
  public record CancelPlan(
      boolean alreadyCancelled,
      boolean refundNeeded,
      BigDecimal amount,
      String currency,
      boolean viaShipments) {
    public CancelPlan(
        boolean alreadyCancelled, boolean refundNeeded, BigDecimal amount, String currency) {
      this(alreadyCancelled, refundNeeded, amount, currency, false);
    }
  }

  public record Claimed(UUID shipmentId, int attempt, BigDecimal rest, String route, String key) {}

  public record CancelClaim(UUID orderId, List<Claimed> claims) {}

  public record CancelCommit(
      boolean changed,
      OrderResponse order,
      ShipmentResponse shipment,
      String previousOrderStatus,
      boolean orderCancelled,
      BigDecimal credit,
      String walletKey,
      UUID userId,
      List<ReturnLine> lines) {}

  /** What a return refund needs to know up front. Wallet, rest and route are null until frozen. */
  public record ReturnRefundPlan(
      UUID returnId,
      UUID orderId,
      UUID userId,
      String currency,
      ReturnStatus status,
      List<ReturnLine> lines,
      BigDecimal wallet,
      BigDecimal rest,
      String route,
      boolean walletDone,
      String restState,
      int attempt,
      String key) {}

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

  /** The live (not cancelled) order made from this cart, if any (TD-BE-15). */
  @Transactional(readOnly = true)
  public Optional<UUID> findActiveOrderForCart(UUID userId, String cartFingerprint) {
    return orders.findActiveIdByCartFingerprint(userId, cartFingerprint);
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

  /** What the COD ensure needs from an order; read-only. */
  public record CodRowSpec(
      UUID userId,
      String currency,
      BigDecimal remainder,
      boolean cod,
      boolean cancelled,
      String paymentId,
      boolean collected) {}

  @Transactional(readOnly = true)
  public Optional<CodRowSpec> codRowSpec(UUID orderId) {
    return orders
        .findById(orderId)
        .map(
            o ->
                new CodRowSpec(
                    o.getUserId(),
                    o.getCurrency(),
                    o.getTotalAmount().subtract(o.getWalletAmount()),
                    o.getPaymentMethod() == PaymentMethod.COD,
                    o.getStatus() == OrderStatus.CANCELLED,
                    o.getPaymentId(),
                    o.getDeliveredAt() != null
                        && List.of(
                                PaymentStatus.COD_COLLECTED,
                                PaymentStatus.PARTIALLY_REFUNDED,
                                PaymentStatus.REFUNDED)
                            .contains(o.getPaymentStatus())));
  }

  /** Attaches the payment id only if none is set; true when this call attached it. */
  @Transactional
  public boolean attachPaymentIfAbsent(UUID orderId, String paymentId) {
    return orders.attachPaymentIfAbsent(orderId, paymentId) == 1;
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
    return adminList(status, term, userId, null, pageable);
  }

  @Transactional(readOnly = true)
  public Page<AdminOrderSummary> adminList(
      OrderStatus status, String term, UUID userId, Boolean riskHold, Pageable pageable) {
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
          if (riskHold != null) {
            ps.add(cb.equal(root.get("riskHold"), riskHold));
          }
          if (!q.isEmpty()) {
            ps.add(
                cb.or(
                    cb.like(cb.lower(root.get("email")), "%" + q + "%"),
                    cb.like(cb.function("text", String.class, root.get("id")), q + "%")));
          }
          return cb.and(ps.toArray(new Predicate[0]));
        };
    Page<Order> page = orders.findAll(spec, pageable);
    // One grouped query for the page instead of lazily loading every order's items (TD-P6-27).
    Map<UUID, Long> itemCounts = new java.util.HashMap<>();
    if (page.hasContent()) {
      for (Object[] row : orders.itemCounts(page.map(Order::getId).getContent())) {
        itemCounts.put((UUID) row[0], ((Number) row[1]).longValue());
      }
    }
    return page.map(
        o ->
            new AdminOrderSummary(
                o.getId(),
                o.getStatus().name(),
                o.getPaymentMethod().name(),
                o.getPaymentStatus().name(),
                o.getEmail(),
                o.getTotalAmount(),
                o.getCurrency(),
                itemCounts.getOrDefault(o.getId(), 0L),
                o.getPlacedAt(),
                o.isRiskHold(),
                o.getRiskScore()));
  }

  /** Admin release of a risk hold (idempotent). */
  @Transactional
  public OrderResponse releaseHold(UUID id) {
    Order o = findAny(id);
    o.releaseHold(Instant.now());
    return mapper.toResponse(o);
  }

  /** Exchange verification outcome: record the shortfall the customer still owes. */
  @Transactional
  public OrderResponse recordExchangeShortfall(UUID id, BigDecimal amount) {
    Order o = findAny(id);
    o.recordExchangeShortfall(amount);
    return mapper.toResponse(o);
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
          ErrorCode.ORDER_NOT_CANCELLABLE, "Order cannot be cancelled in status " + o.getStatus());
    }
    boolean refund =
        o.getPaymentStatus() == PaymentStatus.PAID
            && (o.getPaymentId() != null || o.getWalletAmount().signum() > 0);
    // Paid orders with shipments cancel shipment by shipment (never the cancel-<orderId> refund).
    boolean viaShipments =
        o.getStatus() != OrderStatus.PENDING_PAYMENT && !o.getShipments().isEmpty();
    return new CancelPlan(
        false,
        refund,
        o.getTotalAmount().subtract(o.getWalletAmount()),
        o.getCurrency(),
        viaShipments);
  }

  @Transactional
  public CancelClaim claimCancel(
      UUID ownerOrNull, UUID sellerOrNull, UUID orderId, List<UUID> shipmentIds) {
    Order o =
        orders.lockById(orderId).orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
    if (ownerOrNull != null && !ownerOrNull.equals(o.getUserId())) {
      throw new ResourceNotFoundException("Order", orderId);
    }
    List<OrderShipment> targets =
        shipmentIds == null
            ? o.getShipments()
            : o.getShipments().stream().filter(s -> shipmentIds.contains(s.getId())).toList();
    if (shipmentIds != null && targets.size() != shipmentIds.size()) {
      throw new ResourceNotFoundException("Shipment", shipmentIds.get(0));
    }
    if (sellerOrNull != null
        && targets.stream().anyMatch(s -> !sellerOrNull.equals(s.getSellerId()))) {
      throw new ResourceNotFoundException("Shipment", shipmentIds.get(0));
    }
    List<OrderShipment> live = targets.stream().filter(s -> !s.isCancelled()).toList();
    if (!live.isEmpty() && o.getStatus() == OrderStatus.PENDING_PAYMENT) {
      throw new CodedException(
          ErrorCode.INVALID_STATUS_TRANSITION, "Cannot cancel a shipment of an unpaid order");
    }
    if (!live.isEmpty() && shipmentIds != null && o.isRiskHold()) {
      throw new CodedException(
          ErrorCode.INVALID_STATUS_TRANSITION,
          "Order is on risk hold - an admin must release it first");
    }
    if (live.stream().anyMatch(s -> !s.getStatus().isCancellable())) {
      throw new CodedException(
          ErrorCode.ORDER_NOT_CANCELLABLE, "A shipment past PACKED cannot be cancelled");
    }
    Instant now = Instant.now();
    for (OrderShipment s : live) {
      s.reclaim(now);
      if (!s.holdsCancelClaim()) {
        Order.CancelShare share = o.cancelShare(s);
        Legs legs = legsFor(o, share.total(), null, s);
        s.freezeCancel(
            share.total(),
            share.shipping(),
            legs.wallet(),
            legs.rest(),
            route(o, "SOURCE", legs.rest()),
            now);
      }
    }
    orders.flush();
    return new CancelClaim(
        orderId,
        live.stream()
            .map(
                s ->
                    new Claimed(
                        s.getId(),
                        s.getCancelAttempt(),
                        s.getCancelRestLeg(),
                        s.getCancelRestRoute(),
                        s.cancelKey()))
            .toList());
  }

  @Transactional
  public void failCancel(UUID orderId, UUID shipmentId, int attempt) {
    Order o = orders.lockById(orderId).orElseThrow();
    o.getShipments().stream()
        .filter(s -> s.getId().equals(shipmentId))
        .findFirst()
        .ifPresent(s -> s.failCancel(attempt, Instant.now()));
  }

  @Transactional
  public CancelCommit commitCancel(
      UUID orderId, UUID shipmentId, int attempt, HistoryActor actor, String note) {
    Order o = orders.lockById(orderId).orElseThrow();
    OrderShipment s =
        o.getShipments().stream()
            .filter(x -> x.getId().equals(shipmentId))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));
    String previous = o.getStatus().name();
    Instant now = Instant.now();
    BigDecimal wallet = s.getCancelWalletLeg();
    BigDecimal rest = s.getCancelRestLeg();
    String route = s.getCancelRestRoute();
    if (!o.commitShipmentCancel(s, attempt, actor, note, now)) {
      return new CancelCommit(false, null, null, previous, false, null, null, null, List.of());
    }
    boolean paysRest = !"NONE".equals(route);
    BigDecimal moved = wallet.add(paysRest ? rest : BigDecimal.ZERO);
    boolean all = o.fullyRefunded();
    if (moved.signum() > 0 && o.getPaymentStatus() != PaymentStatus.COD_PENDING) {
      o.recordRefund(moved, all);
    } else if (wallet.signum() > 0) {
      o.addRefunded(wallet); // uncollected COD: only the wallet part was ever paid
    }
    orders.flush();
    BigDecimal credit = wallet.add("DIRECT_WALLET".equals(route) ? rest : BigDecimal.ZERO);
    return new CancelCommit(
        true,
        mapper.toResponse(o),
        mapper.toShipment(o, s, now, props.getReturns().getWindowDays()),
        previous,
        o.getStatus() == OrderStatus.CANCELLED,
        credit,
        s.cancelWalletKey(),
        o.getUserId(),
        o.getItems().stream()
            .filter(i -> shipmentId.equals(i.getShipmentId()))
            .map(
                i ->
                    new ReturnLine(
                        i.getProductId(), i.getVariantId(), i.getQuantity(), i.getOfferId()))
            .toList());
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
    Order o = orders.lockById(id).orElseThrow(() -> new ResourceNotFoundException("Order", id));
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
    // Order row first, so a ship and a cancel of the same order never interleave (F-14).
    UUID orderId =
        shipments
            .findOrderId(shipmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));
    Order o = orders.lockById(orderId).orElseThrow();
    OrderShipment s =
        o.getShipments().stream()
            .filter(x -> x.getId().equals(shipmentId))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));
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
        s.getId(),
        s.getOrder().getId(),
        s.getOrder().getUserId(),
        s.getSellerId(),
        s.getStatus().name());
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
    orders.lockById(orderId); // order row first, like cancel (F-14)
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
    long recent =
        returns.countByUserIdAndCreatedAtAfter(userId, Instant.now().minus(60, ChronoUnit.DAYS));
    long totalOrders = orders.countByUserIdAndStatusNot(userId, OrderStatus.CANCELLED);
    long returnedOrders = returns.countDistinctOrders(userId);
    if (recent > 3 || (totalOrders >= 4 && returnedOrders * 2 > totalOrders)) {
      r.flagAbuse();
    }
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

  /** The return as the admin sees it. */
  @Transactional(readOnly = true)
  public ReturnResponse view(UUID returnId) {
    return mapper.toResponse(findReturn(returnId));
  }

  /** Describes the refund; REFUNDED is allowed (the caller returns early), other states are not. */
  @Transactional(readOnly = true)
  public ReturnRefundPlan refundPlan(UUID returnId) {
    OrderReturn r = findReturn(returnId);
    if (r.getStatus() != ReturnStatus.PICKED_UP && r.getStatus() != ReturnStatus.REFUNDED) {
      throw new CodedException(
          ErrorCode.INVALID_STATUS_TRANSITION,
          "Return is " + r.getStatus() + ", expected PICKED_UP");
    }
    return plan(r);
  }

  private ReturnRefundPlan plan(OrderReturn r) {
    Order o = r.getOrder();
    return new ReturnRefundPlan(
        r.getId(),
        o.getId(),
        o.getUserId(),
        o.getCurrency(),
        r.getStatus(),
        r.getItems().stream()
            .map(
                (OrderReturnItem i) ->
                    new ReturnLine(
                        i.getProductId(),
                        i.getVariantId(),
                        i.getQuantity(),
                        offerOf(o, r.getShipmentId(), i)))
            .toList(),
        r.getWalletLegAmount(),
        r.getRestLegAmount(),
        r.getRestRoute(),
        r.getWalletLegDoneAt() != null,
        r.getRestLegState(),
        r.getRestLegAttempt(),
        r.refundKey());
  }

  /** The 2-minute refund lease stamp (release with it), or null if held or not PICKED_UP. */
  public Instant claimRefundLease(UUID returnId) {
    return returns.claimRefundLease(returnId).orElse(null);
  }

  /** Holder-scoped: a caller whose lease expired and was taken over frees nothing. */
  public void releaseRefundLease(UUID returnId, Instant stamp) {
    returns.releaseRefundLease(returnId, stamp);
  }

  /** Starts the next card-leg key; false when another caller already moved the attempt on. */
  public boolean bumpRestLegAttempt(UUID returnId, int attempt) {
    return returns.bumpRestLegAttempt(returnId, attempt) == 1;
  }

  public List<UUID> pendingRestLegIds() {
    return returns.findPendingRestLegIds();
  }

  /**
   * Freezes the wallet and rest legs on the first attempt (no-op afterwards), so every retry sends
   * the same amounts under the same keys. w = min(round2(R*W/T), W - w of siblings, R); rest = R -
   * w; rest beyond the card remainder moves to the wallet. w + rest = R to the cent.
   */
  @Transactional
  public ReturnRefundPlan freezeLegs(UUID returnId) {
    OrderReturn r = findReturn(returnId);
    if (r.getWalletLegAmount() != null) {
      return plan(r);
    }
    // Siblings are read after this lock, so concurrent freezes see each other (never both first).
    Order o = orders.lockById(r.getOrder().getId()).orElseThrow();
    Legs legs = legsFor(o, r.getRefundAmount().setScale(2, RoundingMode.HALF_UP), r, null);
    r.freezeLegs(legs.wallet(), legs.rest(), route(o, r.getRefundTo(), legs.rest()));
    return plan(r);
  }

  record Legs(BigDecimal wallet, BigDecimal rest) {}

  static Legs legsFor(Order o, BigDecimal refund, OrderReturn self, OrderShipment selfShipment) {
    BigDecimal walletPaid = o.getWalletAmount();
    BigDecimal total = o.getTotalAmount();
    BigDecimal siblingWallet = BigDecimal.ZERO;
    BigDecimal siblingRest = BigDecimal.ZERO;
    for (OrderReturn s : o.getReturns()) {
      if (s == self || (s.getStatus() != ReturnStatus.REFUNDED && s.getWalletLegAmount() == null)) {
        continue;
      }
      BigDecimal sr = s.getRefundAmount().setScale(2, RoundingMode.HALF_UP);
      // A REFUNDED row from before V13 has no frozen legs: it was split by the legacy formula.
      BigDecimal sw =
          s.getWalletLegAmount() != null
              ? s.getWalletLegAmount()
              : walletShare(sr, walletPaid, total);
      siblingWallet = siblingWallet.add(sw);
      siblingRest = siblingRest.add(sr.subtract(sw)); // w + rest = R holds for frozen rows too
    }
    for (OrderShipment c : o.getShipments()) {
      if (c != selfShipment && c.holdsCancelClaim() && c.getCancelWalletLeg() != null) {
        siblingWallet = siblingWallet.add(c.getCancelWalletLeg());
        siblingRest = siblingRest.add(c.getCancelRestLeg());
      }
    }
    return split(refund, walletPaid, total, siblingWallet, siblingRest);
  }

  /** w = min(round2(R*W/T), W - sibling wallet, R); rest = R - w, so w + rest = R. */
  static Legs split(
      BigDecimal refund,
      BigDecimal walletPaid,
      BigDecimal total,
      BigDecimal siblingWallet,
      BigDecimal siblingRest) {
    BigDecimal wallet =
        walletShare(refund, walletPaid, total)
            .min(walletPaid.subtract(siblingWallet))
            .min(refund)
            .max(BigDecimal.ZERO);
    BigDecimal rest = refund.subtract(wallet);
    BigDecimal cardRoom = total.subtract(walletPaid).subtract(siblingRest).max(BigDecimal.ZERO);
    if (rest.compareTo(cardRoom) > 0) {
      wallet = wallet.add(rest.subtract(cardRoom));
      rest = cardRoom;
    }
    return new Legs(wallet, rest);
  }

  private static BigDecimal walletShare(BigDecimal refund, BigDecimal wallet, BigDecimal total) {
    if (wallet.signum() <= 0 || total.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    return refund.multiply(wallet).divide(total, 2, RoundingMode.HALF_UP).min(refund);
  }

  /** Where the non-wallet remainder goes. COD and refundTo=WALLET go through payment-service. */
  static String route(Order o, String refundTo, BigDecimal rest) {
    if (rest.signum() <= 0) {
      return "NONE";
    }
    if (o.getPaymentMethod() == PaymentMethod.WALLET) {
      return "DIRECT_WALLET";
    }
    if (List.of(PaymentStatus.PENDING, PaymentStatus.FAILED, PaymentStatus.COD_PENDING)
        .contains(o.getPaymentStatus())) {
      return "NONE"; // never paid: nothing to give back
    }
    boolean card = o.getPaymentMethod() == PaymentMethod.CARD && o.getPaymentId() != null;
    return card && !"WALLET".equals(refundTo) ? "PAYMENT_SOURCE" : "PAYMENT_WALLET";
  }

  /** The wallet credit is committed before the card leg is attempted (AC-6). */
  @Transactional
  public void markWalletLegDone(UUID returnId) {
    findReturn(returnId).markWalletLegDone(Instant.now());
  }

  /** PENDING, SUCCEEDED or FAILED (never changes the attempt). */
  @Transactional
  public void setRestLegState(UUID returnId, String state) {
    findReturn(returnId).setRestLegState(state, Instant.now());
  }

  /**
   * First half of finalizing, inside the caller's transaction: only a PICKED_UP return can become
   * REFUNDED and the {@code @Version} check rejects a concurrent finalize. The caller adds the
   * stock-release and event outbox rows in the same transaction. This is the single hook for the
   * F-10 coin rows.
   */
  @Transactional
  public ReturnOutcome completeRefund(UUID returnId) {
    OrderReturn r = findReturn(returnId);
    // Lock first: sibling finalizes then run one at a time and each sees the other's status.
    Order o = orders.lockById(r.getOrder().getId()).orElseThrow();
    r.markRefunded(Instant.now());
    // Not by amount: a shipping-carrying return refunded first must not close the order early.
    o.recordRefund(r.getRefundAmount(), o.fullyRefunded());
    orders.flush();
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
