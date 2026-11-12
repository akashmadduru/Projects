package com.acme.ecommerce.payment.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/** Internal: order-service asks for a payment record for an order. Idempotent by orderId. */
public record CreatePaymentRequest(
    @NotNull UUID orderId,
    @NotNull UUID userId,
    @NotNull @Positive BigDecimal amount,
    @NotNull @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
    @NotNull @Pattern(regexp = "^(CARD|COD)$") String method) {}
