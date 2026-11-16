package com.acme.ecommerce.payment.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Customer picks another online provider for a payment that is still unpaid. */
public record SwitchProviderRequest(
    @NotNull @Pattern(regexp = "^(STRIPE|RAZORPAY)$") String provider) {}
