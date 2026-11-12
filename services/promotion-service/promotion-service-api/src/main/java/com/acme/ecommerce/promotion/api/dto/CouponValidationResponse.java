package com.acme.ecommerce.promotion.api.dto;

import java.math.BigDecimal;

/** A coupon that applies, with the discount it gives on the supplied subtotal. */
public record CouponValidationResponse(
    String code, String description, BigDecimal discountAmount, String currency) {}
