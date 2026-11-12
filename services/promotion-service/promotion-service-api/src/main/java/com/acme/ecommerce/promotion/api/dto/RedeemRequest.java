package com.acme.ecommerce.promotion.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

/** Internal: redeem a coupon for an order. Idempotent by orderId. */
public record RedeemRequest(
    @NotBlank String code,
    @NotNull UUID userId,
    @NotNull UUID orderId,
    @NotNull @PositiveOrZero BigDecimal subtotal,
    @NotBlank String currency,
    boolean firstOrder) {}
