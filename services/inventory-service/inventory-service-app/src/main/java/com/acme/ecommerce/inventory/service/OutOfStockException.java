package com.acme.ecommerce.inventory.service;

import com.acme.ecommerce.common.core.error.ApplicationException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 409 OUT_OF_STOCK carrying {@code items:[{productId, requested, available}]}. */
public class OutOfStockException extends ApplicationException {

  private static final long serialVersionUID = 1L;

  public record Shortfall(UUID productId, int requested, int available) {}

  public OutOfStockException(List<Shortfall> shortfalls) {
    super(
        ErrorCode.OUT_OF_STOCK,
        "Insufficient stock for " + shortfalls.size() + " product(s)",
        Map.of(
            "items",
            shortfalls.stream()
                .map(
                    s ->
                        Map.of(
                            "productId", s.productId().toString(),
                            "requested", s.requested(),
                            "available", s.available()))
                .toList()),
        null);
  }
}
