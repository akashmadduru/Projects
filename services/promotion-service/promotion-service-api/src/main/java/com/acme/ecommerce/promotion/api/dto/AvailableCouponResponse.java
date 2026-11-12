package com.acme.ecommerce.promotion.api.dto;

import java.math.BigDecimal;

/** {@code discountAmount} is null when no subtotal was supplied or it is below the minimum. */
public record AvailableCouponResponse(
    String code, String description, BigDecimal discountAmount, BigDecimal minOrderAmount) {}
