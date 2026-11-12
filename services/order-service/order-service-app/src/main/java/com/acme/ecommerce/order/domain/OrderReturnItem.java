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

@Entity
@Table(name = "order_return_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderReturnItem {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "return_id", nullable = false, updatable = false)
  private OrderReturn orderReturn;

  @Column(name = "product_id", nullable = false, updatable = false)
  private UUID productId;

  @Column(name = "name", nullable = false, updatable = false, length = 300)
  private String name;

  @Column(name = "quantity", nullable = false, updatable = false)
  private int quantity;

  @Column(name = "unit_price", nullable = false, updatable = false)
  private BigDecimal unitPrice;

  static OrderReturnItem of(
      OrderReturn parent, UUID productId, String name, int quantity, BigDecimal unitPrice) {
    OrderReturnItem i = new OrderReturnItem();
    i.orderReturn = parent;
    i.productId = productId;
    i.name = name;
    i.quantity = quantity;
    i.unitPrice = unitPrice;
    return i;
  }
}
