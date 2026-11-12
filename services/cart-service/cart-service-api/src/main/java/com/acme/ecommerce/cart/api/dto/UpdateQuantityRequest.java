package com.acme.ecommerce.cart.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** New absolute quantity for an existing cart line. */
public record UpdateQuantityRequest(@Min(1) @Max(99) int quantity) {}
