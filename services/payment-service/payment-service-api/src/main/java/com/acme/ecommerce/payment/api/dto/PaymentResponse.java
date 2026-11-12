package com.acme.ecommerce.payment.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payment as seen by order-service and the owner. {@code status}: REQUIRES_PAYMENT, PROCESSING,
 * SUCCEEDED, FAILED, REFUNDED, PARTIALLY_REFUNDED, COD_PENDING. {@code mode}: STRIPE or SIMULATED.
 * {@code clientSecret}/{@code publishableKey} are only set for CARD payments in STRIPE mode that
 * still need payment.
 */
public record PaymentResponse(
    UUID paymentId,
    UUID orderId,
    String status,
    String mode,
    String method,
    BigDecimal amount,
    String currency,
    BigDecimal refundedAmount,
    String clientSecret,
    String publishableKey) {}
