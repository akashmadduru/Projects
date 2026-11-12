package com.acme.ecommerce.cart.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The cart. {@code currency} is null when the cart is empty. {@code savedItems} are the "save for
 * later" lines (not part of the totals).
 */
public record CartResponse(
    List<CartItemResponse> items,
    String currency,
    int itemCount,
    BigDecimal subtotal,
    List<SavedItemResponse> savedItems) {}
