package com.acme.ecommerce.payment.api.dto;

import java.math.BigDecimal;

/**
 * Payment status after the refund (REFUNDED / PARTIALLY_REFUNDED), total refunded so far, and the
 * status (PENDING, SUCCEEDED, FAILED) of the refund row stored under the request's idempotency key.
 * refundStatus is null from a payment-service that predates it.
 */
public record RefundResponse(String status, BigDecimal refundedAmount, String refundStatus) {}
