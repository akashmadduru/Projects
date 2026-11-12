package com.acme.ecommerce.order.domain;

public enum PaymentStatus {
  PENDING,
  PAID,
  FAILED,
  REFUNDED,
  PARTIALLY_REFUNDED,
  COD_PENDING,
  COD_COLLECTED
}
