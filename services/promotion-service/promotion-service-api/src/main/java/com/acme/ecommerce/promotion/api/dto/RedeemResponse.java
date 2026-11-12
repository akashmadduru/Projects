package com.acme.ecommerce.promotion.api.dto;

import java.math.BigDecimal;

/** Discount applied to the order. */
public record RedeemResponse(BigDecimal discountAmount) {}
