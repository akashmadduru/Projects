package com.acme.ecommerce.promotion.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Admin view of a coupon. */
public record CouponResponse(
    UUID id,
    String code,
    String description,
    String type,
    BigDecimal value,
    BigDecimal maxDiscount,
    BigDecimal minOrderAmount,
    String currency,
    Instant startsAt,
    Instant endsAt,
    Integer totalLimit,
    Integer perUserLimit,
    boolean firstOrderOnly,
    boolean active,
    long redeemedCount,
    Instant createdAt,
    Instant updatedAt) {}
