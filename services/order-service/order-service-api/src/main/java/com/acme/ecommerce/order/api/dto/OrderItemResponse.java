package com.acme.ecommerce.order.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(
    UUID productId,
    String sku,
    String name,
    BigDecimal unitPrice,
    int quantity,
    BigDecimal lineTotal,
    String imageUrl) {}
