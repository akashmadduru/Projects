package com.acme.ecommerce.order.domain;

import com.acme.ecommerce.common.core.error.ResourceConflictException;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** An order: an immutable snapshot of cart lines, prices and the shipping address. */
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

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("name ASC, id ASC")
  private List<OrderItem> items = new ArrayList<>();

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

  public static Order place(
      UUID userId,
      String idempotencyKey,
      UUID addressId,
      ShipTo ship,
      String currency,
      Instant now) {
    Order o = new Order();
    o.userId = userId;
    o.idempotencyKey = idempotencyKey;
    o.addressId = addressId;
    o.status = OrderStatus.PLACED;
    o.currency = currency;
    o.totalAmount = BigDecimal.ZERO;
    o.placedAt = now;
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
    totalAmount = totalAmount.add(item.getLineTotal());
  }

  /** PLACED to CANCELLED; cancelling an already cancelled order is a no-op. */
  public void cancel(Instant now) {
    if (status == OrderStatus.CANCELLED) {
      return;
    }
    if (status != OrderStatus.PLACED) {
      throw new ResourceConflictException("Order cannot be cancelled in status " + status);
    }
    status = OrderStatus.CANCELLED;
    cancelledAt = now;
  }
}
