package com.acme.ecommerce.order.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Checkout request. The user comes from the token. {@code items} present means Buy Now (the cart is
 * neither read nor cleared); absent means cart checkout.
 */
public record PlaceOrderRequest(
    @NotNull UUID addressId,
    @NotNull PaymentMethodRequest paymentMethod,
    @Size(max = 40) String couponCode,
    @Size(min = 1, max = 50) List<@Valid OrderLineRequest> items) {

  public enum PaymentMethodRequest {
    CARD,
    COD
  }
}
