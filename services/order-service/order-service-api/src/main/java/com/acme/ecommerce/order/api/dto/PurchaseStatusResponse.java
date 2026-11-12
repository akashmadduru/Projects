package com.acme.ecommerce.order.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Whether the calling user has bought a product: true when one of their non-cancelled orders
 * contains it. {@code orderId}/{@code purchasedAt} describe the most recent such order and are null
 * when {@code purchased} is false.
 */
public record PurchaseStatusResponse(
    UUID productId, boolean purchased, UUID orderId, Instant purchasedAt) {}
