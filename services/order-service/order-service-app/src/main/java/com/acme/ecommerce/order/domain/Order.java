package com.acme.ecommerce.order.domain;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** An order: an immutable snapshot of lines, prices and the shipping address, plus its lifecycle. */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

  /** The platform seller that owns every pre-marketplace product, offer and stock row. */
  public static final UUID PLATFORM_SELLER = UUID.fromString("00000000-0000-0000-0000-00000000a11e");

  public static final String PLATFORM_SELLER_NAME = "Acme Retail";
  public static final String DEFAULT_WAREHOUSE_CODE = "WH-BLR";
  public static final String DEFAULT_WAREHOUSE_PINCODE = "560001";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private OrderStatus status;

  @Column(name = "currency", nullable = false, updatable = false, columnDefinition = "char(3)")
  private String currency;

  @Column(name = "total_amount", nullable = false, updatable = false)
  private BigDecimal totalAmount;

  @Column(name = "placed_at", nullable = false, updatable = false)
  private Instant placedAt;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
  private String idempotencyKey;

  @Column(name = "address_id", nullable = false, updatable = false)
  private UUID addressId;

  @Column(name = "ship_full_name", nullable = false, updatable = false, length = 120)
  private String shipFullName;

  @Column(name = "ship_phone", updatable = false, length = 32)
  private String shipPhone;

  @Column(name = "ship_line1", nullable = false, updatable = false, length = 200)
  private String shipLine1;

  @Column(name = "ship_line2", updatable = false, length = 200)
  private String shipLine2;

  @Column(name = "ship_city", nullable = false, updatable = false, length = 100)
  private String shipCity;

  @Column(name = "ship_state", updatable = false, length = 100)
  private String shipState;

  @Column(name = "ship_postal_code", nullable = false, updatable = false, length = 20)
  private String shipPostalCode;

  @Column(name = "ship_country", nullable = false, updatable = false, columnDefinition = "char(2)")
  private String shipCountry;

  @Enumerated(EnumType.STRING)
  @Column(name = "payment_method", nullable = false, updatable = false, length = 10)
  private PaymentMethod paymentMethod;

  @Enumerated(EnumType.STRING)
  @Column(name = "payment_status", nullable = false, length = 24)
  private PaymentStatus paymentStatus;

  @Column(name = "payment_id", length = 64)
  private String paymentId;

  @Column(name = "subtotal_amount", nullable = false, updatable = false)
  private BigDecimal subtotalAmount = BigDecimal.ZERO;

  @Column(name = "discount_amount", nullable = false, updatable = false)
  private BigDecimal discountAmount = BigDecimal.ZERO;

  @Column(name = "coupon_code", updatable = false, length = 40)
  private String couponCode;

  @Column(name = "shipping_amount", nullable = false, updatable = false)
  private BigDecimal shippingAmount = BigDecimal.ZERO;

  @Column(name = "tax_amount", nullable = false, updatable = false)
  private BigDecimal taxAmount = BigDecimal.ZERO;

  @Column(name = "tax_label", updatable = false, length = 40)
  private String taxLabel;

  @Column(name = "cgst_amount", updatable = false)
  private BigDecimal cgstAmount;

  @Column(name = "sgst_amount", updatable = false)
  private BigDecimal sgstAmount;

  @Column(name = "igst_amount", updatable = false)
  private BigDecimal igstAmount;

  @Column(name = "refunded_amount", nullable = false)
  private BigDecimal refundedAmount = BigDecimal.ZERO;

  @Column(name = "email", updatable = false, length = 320)
  private String email;

  @Column(name = "estimated_delivery_date")
  private LocalDate estimatedDeliveryDate;

  @Column(name = "tracking_number", length = 100)
  private String trackingNumber;

  @Column(name = "carrier", length = 100)
  private String carrier;

  @Column(name = "delivered_at")
  private Instant deliveredAt;

  @Column(name = "coins_redeemed", nullable = false, updatable = false)
  private int coinsRedeemed;

  @Column(name = "coins_value", nullable = false, updatable = false)
  private BigDecimal coinsValue = BigDecimal.ZERO;

  @Column(name = "wallet_amount", nullable = false, updatable = false)
  private BigDecimal walletAmount = BigDecimal.ZERO;

  @Column(name = "membership_free_shipping", nullable = false, updatable = false)
  private boolean membershipFreeShipping;

  @Column(name = "subscription_id", updatable = false)
  private UUID subscriptionId;

  @Column(name = "risk_score", nullable = false)
  private int riskScore;

  /** JSON array of human-readable risk reasons (text column, parsed by the mapper). */
  @Column(name = "risk_reasons", nullable = false)
  private String riskReasons = "[]";

  @Column(name = "risk_hold", nullable = false)
  private boolean riskHold;

  @Column(name = "exchange_quote_id")
  private UUID exchangeQuoteId;

  @Column(name = "exchange_amount", nullable = false)
  private BigDecimal exchangeAmount = BigDecimal.ZERO;

  @Column(name = "exchange_shortfall", nullable = false)
  private BigDecimal exchangeShortfall = BigDecimal.ZERO;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("name ASC, id ASC")
  private List<OrderItem> items = new ArrayList<>();

  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("createdAt ASC, id ASC")
  private List<OrderStatusHistory> history = new ArrayList<>();

  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
  @OrderBy("createdAt ASC, id ASC")
  private List<OrderReturn> returns = new ArrayList<>();

  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
  @OrderBy("createdAt ASC, id ASC")
  private List<OrderShipment> shipments = new ArrayList<>();

  /** Where a reserved quantity of a line is stocked (reported by inventory-service). */
  public record Allocation(
      UUID productId,
      UUID variantId,
      UUID offerId,
      UUID warehouseId,
      String warehouseCode,
      String warehousePincode,
      int quantity) {}

  /** Shipping address snapshot supplied at placement. */
  public record ShipTo(
      String fullName,
      String phone,
      String line1,
      String line2,
      String city,
      String state,
      String postalCode,
      String country) {}

  /** One line of a return request. */
  public record ReturnLine(UUID productId, UUID variantId, int quantity, UUID offerId) {
    public ReturnLine(UUID productId, UUID variantId, int quantity) {
      this(productId, variantId, quantity, null);
    }

    public ReturnLine(UUID productId, int quantity) {
      this(productId, null, quantity, null);
    }
  }

  /** Starts a draft order; call {@link #addItem} per line, then {@link #begin}. */
  public static Order draft(
      UUID userId,
      String email,
      String idempotencyKey,
      UUID addressId,
      ShipTo ship,
      String currency,
      PaymentMethod method,
      Instant now) {
    Order o = new Order();
    o.userId = userId;
    o.email = email;
    o.idempotencyKey = idempotencyKey;
    o.addressId = addressId;
    o.currency = currency;
    o.paymentMethod = method;
    o.placedAt = now;
    o.totalAmount = BigDecimal.ZERO;
    o.shipFullName = ship.fullName();
    o.shipPhone = ship.phone();
    o.shipLine1 = ship.line1();
    o.shipLine2 = ship.line2();
    o.shipCity = ship.city();
    o.shipState = ship.state();
    o.shipPostalCode = ship.postalCode();
    o.shipCountry = ship.country();
    return o;
  }

  public void addItem(
      UUID productId,
      UUID variantId,
      String variantLabel,
      String sku,
      String name,
      BigDecimal unitPrice,
      int quantity,
      String imageUrl) {
    addItem(productId, variantId, variantLabel, sku, name, unitPrice, quantity, imageUrl, null, null);
  }

  public void addItem(
      UUID productId,
      UUID variantId,
      String variantLabel,
      String sku,
      String name,
      BigDecimal unitPrice,
      int quantity,
      String imageUrl,
      UUID offerId,
      UUID sellerId) {
    OrderItem item = OrderItem.of(
            this, productId, variantId, variantLabel, sku, name, unitPrice, quantity, imageUrl);
    item.tagOffer(offerId, sellerId);
    items.add(item);
    subtotalAmount = subtotalAmount.add(item.getLineTotal());
  }

  /** Applies the computed pricing and sets the initial status (CARD pending, COD placed). */
  public void begin(
      Pricing pricing, String couponCode, LocalDate estimatedDelivery, Instant now) {
    this.subtotalAmount = pricing.subtotal();
    this.discountAmount = pricing.discount();
    this.couponCode = couponCode;
    this.shippingAmount = pricing.shipping();
    this.totalAmount = pricing.total();
    this.taxAmount = pricing.tax();
    this.taxLabel = pricing.taxLabel();
    this.cgstAmount = pricing.cgst();
    this.sgstAmount = pricing.sgst();
    this.igstAmount = pricing.igst();
    this.estimatedDeliveryDate = estimatedDelivery;
    if (paymentMethod == PaymentMethod.COD) {
      this.status = OrderStatus.PLACED;
      this.paymentStatus = PaymentStatus.COD_PENDING;
      record(OrderStatus.PLACED, "Order placed (cash on delivery)", HistoryActor.USER, now);
    } else {
      this.status = OrderStatus.PENDING_PAYMENT;
      this.paymentStatus = PaymentStatus.PENDING;
      record(OrderStatus.PENDING_PAYMENT, "Awaiting payment", HistoryActor.USER, now);
    }
  }

  /** Checkout may turn CARD/COD into WALLET once the wallet turns out to cover the total. */
  public void switchPaymentMethod(PaymentMethod method) {
    this.paymentMethod = method;
  }

  /** Loyalty and wallet parts of the payment, set before {@link #begin}. */
  public void applyMoney(
      int coinsRedeemed,
      BigDecimal coinsValue,
      BigDecimal walletAmount,
      boolean freeShipping,
      UUID subscriptionId) {
    this.coinsRedeemed = coinsRedeemed;
    this.coinsValue = coinsValue;
    this.walletAmount = walletAmount;
    this.membershipFreeShipping = freeShipping;
    this.subscriptionId = subscriptionId;
  }

  /** Phase 10: risk assessment at placement (hold = needs admin release before fulfilment). */
  public void applyRisk(int score, List<String> reasons, boolean hold) {
    this.riskScore = score;
    this.riskReasons =
        "["
            + reasons.stream()
                .map(r -> "\"" + r.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(","))
            + "]";
    this.riskHold = hold;
  }

  /** Admin release of a risk hold. Returns false when the order was not on hold. */
  public boolean releaseHold(Instant now) {
    if (!riskHold) {
      return false;
    }
    riskHold = false;
    record(status, "Risk hold released by admin", HistoryActor.ADMIN, now);
    return true;
  }

  public void applyExchange(UUID quoteId, BigDecimal value) {
    this.exchangeQuoteId = quoteId;
    this.exchangeAmount = value;
  }

  public void recordExchangeShortfall(BigDecimal amount) {
    this.exchangeShortfall = amount;
  }

  /** Items value actually paid for (subtotal minus coupon and coins), the refund ceiling. */
  public BigDecimal paidItemsValue() {
    return subtotalAmount.subtract(discountAmount).subtract(coinsValue);
  }

  public void attachPayment(String paymentId) {
    this.paymentId = paymentId;
  }

  /** Payment succeeded. Returns true when this call changed the order. */
  public boolean markPaid(String paymentId, Instant now) {
    if (status != OrderStatus.PENDING_PAYMENT) {
      return false;
    }
    status = OrderStatus.PLACED;
    paymentStatus = PaymentStatus.PAID;
    shipments.forEach(s -> s.markPaid(now));
    if (paymentId != null) {
      this.paymentId = paymentId;
    }
    record(OrderStatus.PLACED, "Payment received - order placed", HistoryActor.SYSTEM, now);
    return true;
  }

  /** Payment failed. Returns true when this call changed the order. */
  public boolean markPaymentFailed(Instant now) {
    if (status != OrderStatus.PENDING_PAYMENT) {
      return false;
    }
    status = OrderStatus.CANCELLED;
    paymentStatus = PaymentStatus.FAILED;
    cancelledAt = now;
    shipments.forEach(s -> s.cancel(now));
    record(OrderStatus.CANCELLED, "Payment failed", HistoryActor.SYSTEM, now);
    return true;
  }

  /**
   * Cancels the order. Idempotent: an already-cancelled order returns false. Anything past PACKED
   * is a 409 ORDER_NOT_CANCELLABLE.
   */
  public boolean cancel(HistoryActor actor, String note, Instant now) {
    if (status == OrderStatus.CANCELLED) {
      return false;
    }
    if (!isCancellable()) {
      throw new CodedException(
          ErrorCode.ORDER_NOT_CANCELLABLE, "Order cannot be cancelled in status " + status);
    }
    status = OrderStatus.CANCELLED;
    cancelledAt = now;
    shipments.forEach(s -> s.cancel(now));
    if (paymentStatus == PaymentStatus.PENDING) {
      paymentStatus = PaymentStatus.FAILED;
    }
    record(OrderStatus.CANCELLED, note, actor, now);
    return true;
  }

  /** Admin forward-only move along the fulfilment chain. */
  public void advance(
      OrderStatus target, String note, String tracking, String carrierName, Instant now) {
    if (shipments.size() > 1) {
      throw new CodedException(
          ErrorCode.INVALID_STATUS_TRANSITION,
          "This order has several shipments; update each shipment instead");
    }
    if (shipments.size() == 1) {
      advanceShipment(shipments.get(0).getId(), target, note, tracking, carrierName, HistoryActor.ADMIN, now);
      return;
    }
    if (!target.isFulfilmentStep() || !status.isFulfilmentStep()) {
      throw new CodedException(
          ErrorCode.INVALID_STATUS_TRANSITION,
          "Cannot move an order from " + status + " to " + target);
    }
    if (OrderStatus.FULFILMENT.indexOf(target) <= OrderStatus.FULFILMENT.indexOf(status)) {
      throw new CodedException(
          ErrorCode.INVALID_STATUS_TRANSITION,
          "Cannot move an order from " + status + " to " + target);
    }
    if (tracking != null && !tracking.isBlank()) {
      this.trackingNumber = tracking.trim();
    }
    if (carrierName != null && !carrierName.isBlank()) {
      this.carrier = carrierName.trim();
    }
    status = target;
    if (target == OrderStatus.DELIVERED) {
      deliveredAt = now;
      if (paymentMethod == PaymentMethod.COD) {
        paymentStatus = PaymentStatus.COD_COLLECTED;
      }
    }
    record(target, note, HistoryActor.ADMIN, now);
  }

  /** Records a refund against this order and updates the payment status accordingly. */
  public void recordRefund(BigDecimal amount, boolean full) {
    refundedAmount = refundedAmount.add(amount);
    paymentStatus = full ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
  }

  /** A payment landed after the order was cancelled and has been refunded in full. */
  public void recordLateRefund(Instant now) {
    recordRefund(totalAmount, true);
    record(
        OrderStatus.CANCELLED,
        "Payment received after cancellation - refunded",
        HistoryActor.SYSTEM,
        now);
  }

  /** Cancellable as a whole only while no live shipment is past PACKED. */
  public boolean isCancellable() {
    return status.isCancellable() && shipments.stream().allMatch(OrderShipment::isCancellable);
  }

  public Instant returnWindowEndsAt(int windowDays) {
    Instant base =
        shipments.stream()
            .map(OrderShipment::getDeliveredAt)
            .filter(Objects::nonNull)
            .max(Instant::compareTo)
            .orElse(deliveredAt);
    return base == null ? null : base.plus(Duration.ofDays(windowDays));
  }

  public boolean isReturnable(Instant now, int windowDays) {
    if (!shipments.isEmpty()) {
      return shipments.stream().anyMatch(s -> isShipmentReturnable(s, now, windowDays));
    }
    return status == OrderStatus.DELIVERED
        && deliveredAt != null
        && !now.isAfter(deliveredAt.plus(Duration.ofDays(windowDays)))
        && remainingReturnable(null).values().stream().anyMatch(q -> q > 0);
  }

  public boolean isShipmentReturnable(OrderShipment s, Instant now, int windowDays) {
    return s.getStatus() == OrderStatus.DELIVERED
        && s.getDeliveredAt() != null
        && !now.isAfter(s.getDeliveredAt().plus(Duration.ofDays(windowDays)))
        && remainingReturnable(s).values().stream().anyMatch(q -> q > 0);
  }

  /** A returnable line: product plus optional variant. */
  public record LineKey(UUID productId, UUID variantId) {}

  /** Delivered quantity minus quantity in non-rejected returns, per (product, variant). */
  public Map<LineKey, Integer> remainingReturnable() {
    return remainingReturnable(null);
  }

  /** Same, restricted to one shipment (null = whole order). */
  public Map<LineKey, Integer> remainingReturnable(OrderShipment shipment) {
    Map<LineKey, Integer> remaining = new HashMap<>();
    for (OrderItem i : items) {
      if (shipment != null && !shipment.getId().equals(i.getShipmentId())) {
        continue;
      }
      remaining.merge(new LineKey(i.getProductId(), i.getVariantId()), i.getQuantity(), Integer::sum);
    }
    for (OrderReturn r : returns) {
      if (r.getStatus() == ReturnStatus.REJECTED) {
        continue;
      }
      if (shipment != null
          && r.getShipmentId() != null
          && !r.getShipmentId().equals(shipment.getId())) {
        continue;
      }
      for (OrderReturnItem ri : r.getItems()) {
        remaining.merge(new LineKey(ri.getProductId(), ri.getVariantId()), -ri.getQuantity(), Integer::sum);
      }
    }
    return remaining;
  }

  /** Validates and creates a return request; the refund is pro-rated for any order discount. */
  public OrderReturn requestReturn(
      List<ReturnLine> lines,
      ReturnReason reason,
      String comment,
      String refundTo,
      UUID shipmentHint,
      int windowDays,
      Instant now) {
    OrderShipment target = null;
    if (!shipments.isEmpty()) {
      target = resolveReturnShipment(lines, shipmentHint);
      if (target.getStatus() != OrderStatus.DELIVERED || target.getDeliveredAt() == null) {
        throw new CodedException(
            ErrorCode.RETURN_NOT_ALLOWED, "Only delivered shipments can be returned");
      }
      if (now.isAfter(target.getDeliveredAt().plus(Duration.ofDays(windowDays)))) {
        throw new CodedException(
            ErrorCode.RETURN_WINDOW_EXPIRED,
            "The " + windowDays + "-day return window has passed");
      }
    } else {
      if (status != OrderStatus.DELIVERED || deliveredAt == null) {
        throw new CodedException(
            ErrorCode.RETURN_NOT_ALLOWED, "Only delivered orders can be returned");
      }
      if (now.isAfter(returnWindowEndsAt(windowDays))) {
        throw new CodedException(
            ErrorCode.RETURN_WINDOW_EXPIRED,
            "The " + windowDays + "-day return window has passed");
      }
    }
    final OrderShipment scope = target;
    Map<LineKey, Integer> remaining = remainingReturnable(scope);
    Map<LineKey, OrderItem> byLine = new HashMap<>();
    items.stream()
        .filter(i -> scope == null || scope.getId().equals(i.getShipmentId()))
        .forEach(i -> byLine.put(new LineKey(i.getProductId(), i.getVariantId()), i));
    Map<LineKey, Integer> merged = new HashMap<>();
    for (ReturnLine l : lines) {
      LineKey key = new LineKey(l.productId(), l.variantId());
      if (l.variantId() == null && !byLine.containsKey(key)) {
        // Client omitted the variant: resolve it when the product has exactly one variant line.
        List<LineKey> candidates =
            byLine.keySet().stream().filter(k -> k.productId().equals(l.productId())).toList();
        if (candidates.size() == 1) {
          key = candidates.get(0);
        }
      }
      merged.merge(key, l.quantity(), Integer::sum);
    }
    BigDecimal gross = BigDecimal.ZERO;
    for (Map.Entry<LineKey, Integer> e : merged.entrySet()) {
      OrderItem item = byLine.get(e.getKey());
      int left = remaining.getOrDefault(e.getKey(), 0);
      if (item == null || e.getValue() < 1 || e.getValue() > left) {
        throw new CodedException(
            ErrorCode.RETURN_NOT_ALLOWED,
            "Cannot return "
                + e.getValue()
                + " of product "
                + e.getKey().productId()
                + (e.getKey().variantId() == null ? "" : " variant " + e.getKey().variantId())
                + " ("
                + left
                + " left)");
      }
      gross = gross.add(item.getUnitPrice().multiply(BigDecimal.valueOf(e.getValue())));
    }
    BigDecimal refund = gross;
    BigDecimal totalDiscount = discountAmount.add(coinsValue);
    if (subtotalAmount.signum() > 0 && totalDiscount.signum() > 0) {
      refund =
          gross
              .multiply(subtotalAmount.subtract(totalDiscount))
              .divide(subtotalAmount, 2, RoundingMode.HALF_UP);
    }
    OrderReturn r =
        OrderReturn.request(
            this,
            reason,
            comment,
            refund.setScale(2, RoundingMode.HALF_UP),
            refundTo,
            scope == null ? null : scope.getId(),
            now);
    for (Map.Entry<LineKey, Integer> e : merged.entrySet()) {
      OrderItem item = byLine.get(e.getKey());
      r.addItem(
          e.getKey().productId(),
          e.getKey().variantId(),
          item.getName(),
          e.getValue(),
          item.getUnitPrice());
    }
    returns.add(r);
    return r;
  }

  private OrderShipment resolveReturnShipment(List<ReturnLine> lines, UUID hint) {
    if (hint != null) {
      return shipments.stream()
          .filter(s -> s.getId().equals(hint))
          .findFirst()
          .orElseThrow(
              () -> new CodedException(ErrorCode.RETURN_NOT_ALLOWED, "Unknown shipment " + hint));
    }
    if (shipments.size() == 1) {
      return shipments.get(0);
    }
    UUID productId = lines.get(0).productId();
    return shipments.stream()
        .filter(
            s ->
                s.getStatus() == OrderStatus.DELIVERED
                    && items.stream()
                        .anyMatch(
                            i ->
                                s.getId().equals(i.getShipmentId())
                                    && i.getProductId().equals(productId)))
        .findFirst()
        .orElseThrow(
            () ->
                new CodedException(
                    ErrorCode.RETURN_NOT_ALLOWED, "Only delivered shipments can be returned"));
  }

  // ---- shipments -----------------------------------------------------------------------------

  /**
   * Groups the lines by (seller, warehouse) using the reservation result and creates one shipment
   * per group. Lines the reservation did not report on (older inventory-service) go to the
   * default platform warehouse. A line stocked in several warehouses is split. Idempotent.
   */
  public void assignShipments(
      List<Allocation> allocations,
      Map<UUID, String> sellerNames,
      Function<String, LocalDate> etaFor,
      Instant now) {
    if (!shipments.isEmpty()) {
      return;
    }
    Map<ShipGroup, OrderShipment> groups = new LinkedHashMap<>();
    int[] left = allocations.stream().mapToInt(Allocation::quantity).toArray();
    for (OrderItem original : new ArrayList<>(items)) {
      OrderItem current = original;
      int need = original.getQuantity();
      for (int a = 0; a < allocations.size() && need > 0; a++) {
        Allocation al = allocations.get(a);
        if (left[a] <= 0 || !matches(original, al)) {
          continue;
        }
        int take = Math.min(need, left[a]);
        OrderItem target = current;
        if (take < current.getQuantity()) {
          target = current.splitOff(take);
          items.add(target);
        }
        attach(target, al, groups, sellerNames, etaFor, now);
        left[a] -= take;
        need -= take;
      }
      if (need > 0) {
        attach(current, null, groups, sellerNames, etaFor, now);
      }
    }
    LocalDate latest = null;
    for (OrderShipment s : groups.values()) {
      BigDecimal amount =
          items.stream()
              .filter(i -> i.getShipment() == s)
              .map(OrderItem::getLineTotal)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      s.setAmount(amount);
      if (s.getEstimatedDeliveryDate() != null
          && (latest == null || s.getEstimatedDeliveryDate().isAfter(latest))) {
        latest = s.getEstimatedDeliveryDate();
      }
    }
    if (latest != null) {
      this.estimatedDeliveryDate = latest;
    }
  }

  private record ShipGroup(UUID sellerId, String warehouse) {}

  private static boolean matches(OrderItem item, Allocation al) {
    return item.getProductId().equals(al.productId())
        && Objects.equals(item.getVariantId(), al.variantId())
        && (al.offerId() == null
            || item.getOfferId() == null
            || al.offerId().equals(item.getOfferId()));
  }

  private void attach(
      OrderItem item,
      Allocation al,
      Map<ShipGroup, OrderShipment> groups,
      Map<UUID, String> sellerNames,
      Function<String, LocalDate> etaFor,
      Instant now) {
    UUID seller = item.getSellerId() == null ? PLATFORM_SELLER : item.getSellerId();
    UUID whId = al == null ? null : al.warehouseId();
    String code =
        al == null || al.warehouseCode() == null || al.warehouseCode().isBlank()
            ? (whId == null ? DEFAULT_WAREHOUSE_CODE : whId.toString())
            : al.warehouseCode();
    String pin =
        al == null || al.warehousePincode() == null || al.warehousePincode().isBlank()
            ? DEFAULT_WAREHOUSE_PINCODE
            : al.warehousePincode();
    ShipGroup key = new ShipGroup(seller, code);
    OrderShipment shipment = groups.get(key);
    if (shipment == null) {
      String name = sellerNames.get(seller);
      if (name == null && PLATFORM_SELLER.equals(seller)) {
        name = PLATFORM_SELLER_NAME;
      }
      shipment =
          OrderShipment.of(this, seller, name, whId, code, pin, status, etaFor.apply(pin), now);
      shipments.add(shipment);
      groups.put(key, shipment);
    }
    item.assignTo(shipment);
    if (item.getSellerId() == null) {
      item.tagOffer(item.getOfferId(), seller);
    }
  }

  /** Admin/seller move of ONE shipment; the order status is re-derived from all its shipments. */
  public OrderShipment advanceShipment(
      UUID shipmentId,
      OrderStatus target,
      String note,
      String tracking,
      String carrierName,
      HistoryActor actor,
      Instant now) {
    OrderShipment s =
        shipments.stream()
            .filter(x -> x.getId().equals(shipmentId))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));
    if (status == OrderStatus.PENDING_PAYMENT || status == OrderStatus.CANCELLED) {
      throw new CodedException(
          ErrorCode.INVALID_STATUS_TRANSITION,
          "Cannot move a shipment of an order in status " + status);
    }
    if (riskHold) {
      throw new CodedException(
          ErrorCode.INVALID_STATUS_TRANSITION,
          "Order is on risk hold - an admin must release it first");
    }
    s.advance(target, tracking, carrierName, now);
    record(target, note, actor, now, s.getId());
    syncFromShipments(now);
    return s;
  }

  /** Order status = DELIVERED when all live shipments are, else the minimum live shipment status. */
  private void syncFromShipments(Instant now) {
    List<OrderShipment> live =
        shipments.stream().filter(s -> s.getStatus() != OrderStatus.CANCELLED).toList();
    if (live.isEmpty()) {
      return;
    }
    OrderStatus derived;
    if (live.stream().allMatch(s -> s.getStatus() == OrderStatus.DELIVERED)) {
      derived = OrderStatus.DELIVERED;
    } else {
      derived =
          live.stream()
              .map(OrderShipment::getStatus)
              .filter(OrderStatus::isFulfilmentStep)
              .min(java.util.Comparator.comparingInt(OrderStatus.FULFILMENT::indexOf))
              .orElse(status);
    }
    if (derived != status) {
      status = derived;
      if (derived == OrderStatus.DELIVERED) {
        deliveredAt = now;
        if (paymentMethod == PaymentMethod.COD) {
          paymentStatus = PaymentStatus.COD_COLLECTED;
        }
      }
    }
    if (shipments.size() == 1) {
      OrderShipment only = shipments.get(0);
      this.trackingNumber = only.getTrackingNumber();
      this.carrier = only.getCarrier();
    }
  }

  private void record(OrderStatus s, String note, HistoryActor actor, Instant at) {
    record(s, note, actor, at, null);
  }

  private void record(OrderStatus s, String note, HistoryActor actor, Instant at, UUID shipmentId) {
    history.add(OrderStatusHistory.of(this, s, note, actor, at, shipmentId));
  }
}
