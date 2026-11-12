package com.acme.ecommerce.promotion.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * {@code firstOrder} optional: null means "unknown", first-order-only is then enforced at redeem.
 */
public record ValidateCouponRequest(
    @NotBlank @Size(max = 40) String code,
    @NotNull @PositiveOrZero BigDecimal subtotal,
    @NotBlank @Size(min = 3, max = 3) String currency,
    Boolean firstOrder) {}
