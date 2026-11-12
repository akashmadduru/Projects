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

/** Idempotency ledger row for a partial restock. */
@Entity
@Table(name = "reservation_releases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationRelease {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "order_id", nullable = false, updatable = false)
  private UUID orderId;

  @Column(name = "release_key", nullable = false, updatable = false, length = 100)
  private String releaseKey;

  @Column(name = "product_id", nullable = false, updatable = false)
  private UUID productId;

  @Column(name = "quantity", nullable = false, updatable = false)
  private int quantity;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static ReservationRelease of(
      UUID orderId, String releaseKey, UUID productId, int quantity, Instant now) {
    ReservationRelease r = new ReservationRelease();
    r.orderId = orderId;
    r.releaseKey = releaseKey;
    r.productId = productId;
    r.quantity = quantity;
    r.createdAt = now;
    return r;
  }
}
