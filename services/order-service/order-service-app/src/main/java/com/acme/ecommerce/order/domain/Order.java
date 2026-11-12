package com.acme.ecommerce.order.domain;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** An order: an immutable snapshot of lines, prices and the shipping address, plus its lifecycle. */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

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
  public record ReturnLine(UUID productId, int quantity) {}

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
      String sku,
      String name,
      BigDecimal unitPrice,
      int quantity,
      String imageUrl) {
    OrderItem item = OrderItem.of(this, productId, sku, name, unitPrice, quantity, imageUrl);
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
    if (!status.isCancellable()) {
      throw new CodedException(
          ErrorCode.ORDER_NOT_CANCELLABLE, "Order cannot be cancelled in status " + status);
    }
    status = OrderStatus.CANCELLED;
    cancelledAt = now;
    if (paymentStatus == PaymentStatus.PENDING) {
      paymentStatus = PaymentStatus.FAILED;
    }
    record(OrderStatus.CANCELLED, note, actor, now);
    return true;
  }

  /** Admin forward-only move along the fulfilment chain. */
  public void advance(
      OrderStatus target, String note, String tracking, String carrierName, Instant now) {
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

  public boolean isCancellable() {
    return status.isCancellable();
  }

  public Instant returnWindowEndsAt(int windowDays) {
    return deliveredAt == null ? null : deliveredAt.plus(Duration.ofDays(windowDays));
  }

  public boolean isReturnable(Instant now, int windowDays) {
    return status == OrderStatus.DELIVERED
        && deliveredAt != null
        && !now.isAfter(returnWindowEndsAt(windowDays))
        && remainingReturnable().values().stream().anyMatch(q -> q > 0);
  }

  /** Delivered quantity minus quantity in non-rejected returns, per product. */
  public Map<UUID, Integer> remainingReturnable() {
    Map<UUID, Integer> remaining = new HashMap<>();
    for (OrderItem i : items) {
      remaining.merge(i.getProductId(), i.getQuantity(), Integer::sum);
    }
    for (OrderReturn r : returns) {
      if (r.getStatus() == ReturnStatus.REJECTED) {
        continue;
      }
      for (OrderReturnItem ri : r.getItems()) {
        remaining.merge(ri.getProductId(), -ri.getQuantity(), Integer::sum);
      }
    }
    return remaining;
  }

  /** Validates and creates a return request; the refund is pro-rated for any order discount. */
  public OrderReturn requestReturn(
      List<ReturnLine> lines, ReturnReason reason, String comment, int windowDays, Instant now) {
    if (status != OrderStatus.DELIVERED || deliveredAt == null) {
      throw new CodedException(
          ErrorCode.RETURN_NOT_ALLOWED, "Only delivered orders can be returned");
    }
    if (now.isAfter(returnWindowEndsAt(windowDays))) {
      throw new CodedException(
          ErrorCode.RETURN_WINDOW_EXPIRED,
          "The " + windowDays + "-day return window has passed");
    }
    Map<UUID, Integer> remaining = remainingReturnable();
    Map<UUID, OrderItem> byProduct = new HashMap<>();
    items.forEach(i -> byProduct.put(i.getProductId(), i));
    Map<UUID, Integer> merged = new HashMap<>();
    for (ReturnLine l : lines) {
      merged.merge(l.productId(), l.quantity(), Integer::sum);
    }
    BigDecimal gross = BigDecimal.ZERO;
    for (Map.Entry<UUID, Integer> e : merged.entrySet()) {
      OrderItem item = byProduct.get(e.getKey());
      int left = remaining.getOrDefault(e.getKey(), 0);
      if (item == null || e.getValue() < 1 || e.getValue() > left) {
        throw new CodedException(
            ErrorCode.RETURN_NOT_ALLOWED,
            "Cannot return " + e.getValue() + " of product " + e.getKey() + " (" + left + " left)");
      }
      gross = gross.add(item.getUnitPrice().multiply(BigDecimal.valueOf(e.getValue())));
    }
    BigDecimal refund = gross;
    if (subtotalAmount.signum() > 0 && discountAmount.signum() > 0) {
      refund =
          gross
              .multiply(subtotalAmount.subtract(discountAmount))
              .divide(subtotalAmount, 2, RoundingMode.HALF_UP);
    }
    OrderReturn r =
        OrderReturn.request(this, reason, comment, refund.setScale(2, RoundingMode.HALF_UP), now);
    for (Map.Entry<UUID, Integer> e : merged.entrySet()) {
      OrderItem item = byProduct.get(e.getKey());
      r.addItem(e.getKey(), item.getName(), e.getValue(), item.getUnitPrice());
    }
    returns.add(r);
    return r;
  }

  private void record(OrderStatus s, String note, HistoryActor actor, Instant at) {
    history.add(OrderStatusHistory.of(this, s, note, actor, at));
  }
}
