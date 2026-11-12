package com.acme.ecommerce.order.domain;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A customer return request against a delivered order. */
@Entity
@Table(name = "order_returns")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderReturn {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false, updatable = false)
  private Order order;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ReturnStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason", nullable = false, updatable = false, length = 30)
  private ReturnReason reason;

  @Column(name = "comment", updatable = false, length = 1000)
  private String comment;

  @Column(name = "refund_amount", nullable = false, updatable = false)
  private BigDecimal refundAmount;

  @Column(name = "admin_note", length = 1000)
  private String adminNote;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  @OneToMany(mappedBy = "orderReturn", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<OrderReturnItem> items = new ArrayList<>();

  static OrderReturn request(
      Order order, ReturnReason reason, String comment, BigDecimal refundAmount, Instant now) {
    OrderReturn r = new OrderReturn();
    r.order = order;
    r.userId = order.getUserId();
    r.status = ReturnStatus.REQUESTED;
    r.reason = reason;
    r.comment = comment;
    r.refundAmount = refundAmount;
    r.createdAt = now;
    r.updatedAt = now;
    return r;
  }

  void addItem(UUID productId, String name, int quantity, BigDecimal unitPrice) {
    items.add(OrderReturnItem.of(this, productId, name, quantity, unitPrice));
  }

  public void decide(boolean approve, String note, Instant now) {
    requireStatus(ReturnStatus.REQUESTED);
    status = approve ? ReturnStatus.APPROVED : ReturnStatus.REJECTED;
    adminNote = note;
    updatedAt = now;
  }

  public void markPickedUp(Instant now) {
    requireStatus(ReturnStatus.APPROVED);
    status = ReturnStatus.PICKED_UP;
    updatedAt = now;
  }

  public void markRefunded(Instant now) {
    requireStatus(ReturnStatus.PICKED_UP);
    status = ReturnStatus.REFUNDED;
    updatedAt = now;
  }

  private void requireStatus(ReturnStatus expected) {
    if (status != expected) {
      throw new CodedException(
          ErrorCode.INVALID_STATUS_TRANSITION, "Return is " + status + ", expected " + expected);
    }
  }
}
