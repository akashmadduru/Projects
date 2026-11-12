package com.acme.ecommerce.payment.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** SIMULATED mode only: pretend the card payment finished. */
public record SimulatePaymentRequest(
    @NotNull @Pattern(regexp = "^(SUCCEEDED|FAILED)$") String outcome) {}
