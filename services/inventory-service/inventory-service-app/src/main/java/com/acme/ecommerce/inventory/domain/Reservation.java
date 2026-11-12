package com.acme.ecommerce.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Stock taken for one product of one order; restoredQuantity tracks what was put back. */
@Entity
@Table(name = "reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "order_id", nullable = false, updatable = false)
  private UUID orderId;

  @Column(name = "product_id", nullable = false, updatable = false)
  private UUID productId;

  @Column(name = "quantity", nullable = false, updatable = false)
  private int quantity;

  @Column(name = "restored_quantity", nullable = false)
  private int restoredQuantity;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static Reservation of(UUID orderId, UUID productId, int quantity, Instant now) {
    Reservation r = new Reservation();
    r.orderId = orderId;
    r.productId = productId;
    r.quantity = quantity;
    r.createdAt = now;
    return r;
  }

  public int outstanding() {
    return quantity - restoredQuantity;
  }

  /** Marks up to {@code amount} as restored and returns how much was actually restored. */
  public int restore(int amount) {
    int actual = Math.min(amount, outstanding());
    restoredQuantity += actual;
    return actual;
  }
}
