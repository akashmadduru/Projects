package com.acme.ecommerce.order.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Checkout request. The cart and the user come from the token, never from the body. */
public record PlaceOrderRequest(@NotNull UUID addressId) {}
