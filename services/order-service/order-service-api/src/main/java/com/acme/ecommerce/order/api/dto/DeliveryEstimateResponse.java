package com.acme.ecommerce.order.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DeliveryEstimateResponse(
    String pincode,
    boolean serviceable,
    int estimatedDays,
    LocalDate estimatedDeliveryDate,
    BigDecimal shippingFee,
    BigDecimal freeShippingThreshold,
    String currency,
    boolean codAvailable) {}
