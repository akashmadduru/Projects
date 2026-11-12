package com.acme.ecommerce.order.event;

public enum OrderEventType {
  ORDER_PLACED,
  ORDER_STATUS_CHANGED,
  ORDER_CANCELLED,
  RETURN_REQUESTED,
  RETURN_REFUNDED
}
