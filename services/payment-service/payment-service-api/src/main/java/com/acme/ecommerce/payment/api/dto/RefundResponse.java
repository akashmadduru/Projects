package com.acme.ecommerce.payment.api.dto;

import java.math.BigDecimal;

/** Payment status after the refund (REFUNDED / PARTIALLY_REFUNDED) and total refunded so far. */
public record RefundResponse(String status, BigDecimal refundedAmount) {}
