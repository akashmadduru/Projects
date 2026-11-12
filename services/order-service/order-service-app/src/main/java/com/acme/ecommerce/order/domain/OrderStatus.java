package com.acme.ecommerce.order.domain;

import java.util.List;

public enum OrderStatus {
  PENDING_PAYMENT,
  PLACED,
  CONFIRMED,
  PACKED,
  SHIPPED,
  OUT_FOR_DELIVERY,
  DELIVERED,
  CANCELLED;

  /** The forward fulfilment chain, in order. */
  public static final List<OrderStatus> FULFILMENT =
      List.of(PLACED, CONFIRMED, PACKED, SHIPPED, OUT_FOR_DELIVERY, DELIVERED);

  public boolean isFulfilmentStep() {
    return FULFILMENT.contains(this);
  }

  public boolean isCancellable() {
    return this == PENDING_PAYMENT || this == PLACED || this == CONFIRMED || this == PACKED;
  }
}
