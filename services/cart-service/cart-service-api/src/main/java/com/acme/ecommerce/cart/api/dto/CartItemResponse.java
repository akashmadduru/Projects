package com.acme.ecommerce.cart.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CartItemResponse(
    UUID productId,
    int quantity,
    String name,
    BigDecimal unitPrice,
    String currency,
    String imageUrl,
    BigDecimal lineTotal,
    Instant addedAt,
    UUID variantId,
    String variantLabel,
    UUID offerId,
    UUID sellerId) {}
