package com.acme.ecommerce.payment.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payment as seen by order-service and the owner. {@code status}: REQUIRES_PAYMENT, PROCESSING,
 * SUCCEEDED, FAILED, REFUNDED, PARTIALLY_REFUNDED, COD_PENDING. {@code mode}: STRIPE, RAZORPAY or
 * SIMULATED. {@code purpose} is ORDER, MEMBERSHIP or GIFT_CARD ({@code orderId} then holds the
 * subject id). For CARD payments that still need payment: STRIPE sets {@code clientSecret} and
 * {@code publishableKey}; RAZORPAY sets {@code providerOrderId} and {@code publishableKey} (the
 * Razorpay key id).
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
    String publishableKey,
    String purpose,
    String providerOrderId) {}
