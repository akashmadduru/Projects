package com.acme.ecommerce.order.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Checkout request. The user comes from the token. {@code items} present means Buy Now (the cart is
 * neither read nor cleared); absent means cart checkout. {@code useWallet} applies the wallet
 * balance (partial or full); {@code coinsToRedeem} spends loyalty coins (max 20% of the subtotal).
 * {@code paymentMethod} WALLET requires the wallet to cover the whole total.
 */
public record PlaceOrderRequest(
    @NotNull UUID addressId,
    @NotNull PaymentMethodRequest paymentMethod,
    @Size(max = 40) String couponCode,
    @Size(min = 1, max = 50) List<@Valid OrderLineRequest> items,
    Boolean useWallet,
    @Min(0) Integer coinsToRedeem,
    UUID exchangeQuoteId) {

  public enum PaymentMethodRequest {
    CARD,
    COD,
    WALLET
  }

  /** Phase 6/7 callers that predate wallet + coins. */
  public PlaceOrderRequest(
      UUID addressId,
      PaymentMethodRequest paymentMethod,
      String couponCode,
      List<OrderLineRequest> items) {
    this(addressId, paymentMethod, couponCode, items, null, null, null);
  }

  /** Pre-exchange callers (wallet + coins, no exchange quote). */
  public PlaceOrderRequest(
      UUID addressId,
      PaymentMethodRequest paymentMethod,
      String couponCode,
      List<OrderLineRequest> items,
      Boolean useWallet,
      Integer coinsToRedeem) {
    this(addressId, paymentMethod, couponCode, items, useWallet, coinsToRedeem, null);
  }
}
