package com.acme.ecommerce.cart.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WishlistItemResponse(
    UUID productId,
    String name,
    BigDecimal price,
    String currency,
    String imageUrl,
    Instant addedAt,
    UUID variantId,
    String variantLabel) {}
