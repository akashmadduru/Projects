package com.acme.ecommerce.order.domain;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One parcel of an order: the lines of one seller shipped from one warehouse. */
@Entity
@Table(name = "order_shipments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderShipment {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false, updatable = false)
  private Order order;

  @Column(name = "seller_id", nullable = false, updatable = false)
  private UUID sellerId;

  @Column(name = "seller_name", length = 200, updatable = false)
  private String sellerName;

  @Column(name = "warehouse_id", updatable = false)
  private UUID warehouseId;

  @Column(name = "warehouse_code", length = 32, updatable = false)
  private String warehouseCode;

  @Column(name = "warehouse_pincode", length = 20, updatable = false)
  private String warehousePincode;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private OrderStatus status;

  @Column(name = "tracking_number", length = 100)
  private String trackingNumber;

  @Column(name = "carrier", length = 100)
  private String carrier;

  @Column(name = "estimated_delivery_date")
  private LocalDate estimatedDeliveryDate;

  @Column(name = "delivered_at")
  private Instant deliveredAt;

  @Column(name = "amount", nullable = false)
  private BigDecimal amount = BigDecimal.ZERO;

  @Column(name = "cancel_refund_amount")
  private BigDecimal cancelRefundAmount;

  @Column(name = "cancel_shipping_refund", nullable = false)
  private BigDecimal cancelShippingRefund = BigDecimal.ZERO;

  @Column(name = "cancel_wallet_leg")
  private BigDecimal cancelWalletLeg;

  @Column(name = "cancel_rest_leg")
  private BigDecimal cancelRestLeg;

  @Column(name = "cancel_rest_route", length = 16)
  private String cancelRestRoute;

  @Column(name = "cancel_state", length = 12)
  private String cancelState;

  @Column(name = "cancel_attempt", nullable = false)
  private int cancelAttempt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  static OrderShipment of(
      Order order,
      UUID sellerId,
      String sellerName,
      UUID warehouseId,
      String warehouseCode,
      String warehousePincode,
      OrderStatus status,
      LocalDate eta,
      Instant now) {
    OrderShipment s = new OrderShipment();
    s.order = order;
    s.sellerId = sellerId;
    s.sellerName = sellerName;
    s.warehouseId = warehouseId;
    s.warehouseCode = warehouseCode;
    s.warehousePincode = warehousePincode;
    s.status = status;
    s.estimatedDeliveryDate = eta;
    s.createdAt = now;
    s.updatedAt = now;
    return s;
  }

  void setAmount(BigDecimal value) {
    this.amount = value;
  }

  /** Forward-only move along the fulfilment chain (same lifecycle as an order). */
  void advance(OrderStatus target, String tracking, String carrierName, Instant now) {
    if (!target.isFulfilmentStep() || !status.isFulfilmentStep()) {
      throw new CodedException(
          ErrorCode.INVALID_STATUS_TRANSITION,
          "Cannot move a shipment from " + status + " to " + target);
    }
    if (OrderStatus.FULFILMENT.indexOf(target) <= OrderStatus.FULFILMENT.indexOf(status)) {
      throw new CodedException(
          ErrorCode.INVALID_STATUS_TRANSITION,
          "Cannot move a shipment from " + status + " to " + target);
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
    }
    updatedAt = now;
  }

  void markPaid(Instant now) {
    if (status == OrderStatus.PENDING_PAYMENT) {
      status = OrderStatus.PLACED;
      updatedAt = now;
    }
  }

  void cancel(Instant now) {
    if (status != OrderStatus.CANCELLED) {
      status = OrderStatus.CANCELLED;
      updatedAt = now;
    }
  }

  public void freezeCancel(
      BigDecimal refund,
      BigDecimal shipping,
      BigDecimal wallet,
      BigDecimal rest,
      String route,
      Instant now) {
    cancelRefundAmount = refund;
    cancelShippingRefund = shipping;
    cancelWalletLeg = wallet;
    cancelRestLeg = rest;
    cancelRestRoute = route;
    cancelState = "REQUESTED";
    updatedAt = now;
  }

  /** A FAILED claim moved no money: it starts over with the next key and is frozen again. */
  public void reclaim(Instant now) {
    if ("FAILED".equals(cancelState)) {
      cancelAttempt++;
      cancelState = null;
      updatedAt = now;
    }
  }

  public boolean failCancel(int attempt, Instant now) {
    if (!"REQUESTED".equals(cancelState) || cancelAttempt != attempt) {
      return false;
    }
    cancelState = "FAILED";
    updatedAt = now;
    return true;
  }

  boolean commitCancel(int attempt, Instant now) {
    if (!"REQUESTED".equals(cancelState) || cancelAttempt != attempt) {
      return false;
    }
    status = OrderStatus.CANCELLED;
    cancelState = "DONE";
    updatedAt = now;
    return true;
  }

  void dropFailedCancel() {
    if ("FAILED".equals(cancelState)) {
      cancelAttempt++;
      cancelRefundAmount = null;
      cancelShippingRefund = BigDecimal.ZERO;
      cancelWalletLeg = null;
      cancelRestLeg = null;
      cancelRestRoute = null;
      cancelState = null;
    }
  }

  /** Card-leg key: attempt 0 is the plain key, later ones add -r&lt;n&gt;. */
  public String cancelKey() {
    return "ship-cancel-" + id + (cancelAttempt == 0 ? "" : "-r" + cancelAttempt);
  }

  public String cancelWalletKey() {
    return "ship-cancel-wallet-" + id;
  }

  public boolean holdsCancelClaim() {
    return cancelState != null && !"FAILED".equals(cancelState); // FAILED moved no money
  }

  public boolean isCancelled() {
    return status == OrderStatus.CANCELLED;
  }

  public boolean isCancellable() {
    return status == OrderStatus.CANCELLED || status.isCancellable();
  }
}
