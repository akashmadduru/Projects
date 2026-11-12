package com.acme.ecommerce.promotion.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/** Admin create/update body. {@code code} is ignored on update. FLAT requires a currency. */
public record CouponRequest(
    @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String code,
    @Size(max = 300) String description,
    @NotNull @Pattern(regexp = "^(PERCENT|FLAT)$") String type,
    @NotNull @Positive BigDecimal value,
    @Positive BigDecimal maxDiscount,
    @PositiveOrZero BigDecimal minOrderAmount,
    @Size(min = 3, max = 3) String currency,
    Instant startsAt,
    Instant endsAt,
    @Positive Integer totalLimit,
    @Positive Integer perUserLimit,
    Boolean firstOrderOnly,
    Boolean active) {}
