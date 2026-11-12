package com.acme.ecommerce.order.domain;

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
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One row of the order tracking timeline. */
@Entity
@Table(name = "order_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderStatusHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false, updatable = false)
  private Order order;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, updatable = false, length = 20)
  private OrderStatus status;

  @Column(name = "note", updatable = false, length = 500)
  private String note;

  @Enumerated(EnumType.STRING)
  @Column(name = "actor", nullable = false, updatable = false, length = 10)
  private HistoryActor actor;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  static OrderStatusHistory of(
      Order order, OrderStatus status, String note, HistoryActor actor, Instant at) {
    OrderStatusHistory h = new OrderStatusHistory();
    h.order = order;
    h.status = status;
    h.note = note;
    h.actor = actor;
    h.createdAt = at;
    return h;
  }
}
