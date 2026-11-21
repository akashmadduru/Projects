package com.acme.ecommerce.cart.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The cart lines an order consumed (internal, order-service to cart-service). A line is removed
 * only when it still matches exactly; {@code orderId} is for tracing and is never used to scope.
 */
public record ConsumedLinesRequest(
    @NotNull UUID orderId, @NotNull @Size(max = 50) List<@Valid @NotNull Line> lines) {

  /** One consumed line, as it was read from the cart when the order was placed. */
  public record Line(
      @NotNull UUID productId,
      UUID variantId,
      UUID offerId,
      @Min(1) @Max(99) int quantity,
      @NotNull Instant addedAt) {}
}
