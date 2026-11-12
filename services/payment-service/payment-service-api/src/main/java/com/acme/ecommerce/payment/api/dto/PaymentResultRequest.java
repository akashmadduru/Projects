package com.acme.ecommerce.payment.api.dto;

import java.util.UUID;

/**
 * Body payment-service posts to order-service {@code /internal/orders/{id}/payment-result}. {@code
 * status} is SUCCEEDED or FAILED.
 */
public record PaymentResultRequest(String status, UUID paymentId) {}
