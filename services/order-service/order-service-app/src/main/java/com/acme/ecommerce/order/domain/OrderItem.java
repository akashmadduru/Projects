package com.acme.ecommerce.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One immutable order line (price and name captured at placement). */
@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false, updatable = false)
  private Order order;

  @Column(name = "product_id", nullable = false, updatable = false)
  private UUID productId;

  @Column(name = "variant_id", updatable = false)
  private UUID variantId;

  @Column(name = "variant_label", length = 200, updatable = false)
  private String variantLabel;

  @Column(name = "sku", length = 64, updatable = false)
  private String sku;

  @Column(name = "name", nullable = false, length = 300, updatable = false)
  private String name;

  @Column(name = "unit_price", nullable = false, updatable = false)
  private BigDecimal unitPrice;

  @Column(name = "quantity", nullable = false)
  private int quantity;

  @Column(name = "line_total", nullable = false)
  private BigDecimal lineTotal;

  @Column(name = "image_url", length = 2048, updatable = false)
  private String imageUrl;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "shipment_id")
  private OrderShipment shipment;

  @Column(name = "offer_id", updatable = false)
  private UUID offerId;

  @Column(name = "seller_id", updatable = false)
  private UUID sellerId;

  /** The shipment id (the lazy proxy exposes its key without a query). */
  public UUID getShipmentId() {
    return shipment == null ? null : shipment.getId();
  }

  void assignTo(OrderShipment s) {
    this.shipment = s;
  }

  void tagOffer(UUID offer, UUID seller) {
    this.offerId = offer;
    this.sellerId = seller;
  }

  /** Splits quantity off this line (same unit price); returns the new line holding {@code qty}. */
  OrderItem splitOff(int qty) {
    OrderItem n = of(order, productId, variantId, variantLabel, sku, name, unitPrice, qty, imageUrl);
    n.offerId = offerId;
    n.sellerId = sellerId;
    this.quantity -= qty;
    this.lineTotal = unitPrice.multiply(BigDecimal.valueOf(this.quantity));
    return n;
  }

  public static OrderItem of(
      Order order,
      UUID productId,
      UUID variantId,
      String variantLabel,
      String sku,
      String name,
      BigDecimal unitPrice,
      int quantity,
      String imageUrl) {
    OrderItem item = new OrderItem();
    item.order = order;
    item.productId = productId;
    item.variantId = variantId;
    item.variantLabel = variantLabel;
    item.sku = sku;
    item.name = name;
    item.unitPrice = unitPrice;
    item.quantity = quantity;
    item.lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
    item.imageUrl = imageUrl;
    return item;
  }
}
