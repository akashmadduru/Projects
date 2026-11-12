package com.acme.ecommerce.cart.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A line parked in "save for later". Price is the snapshot taken when it was saved. */
public record SavedItemResponse(
    UUID productId,
    String name,
    BigDecimal unitPrice,
    String currency,
    String imageUrl,
    Instant savedAt,
    UUID variantId,
    String variantLabel,
    UUID offerId,
    UUID sellerId) {}
