package com.acme.ecommerce.order.api.dto;

/** Payment details returned with a CARD order so the client can complete payment. */
public record PaymentBlockResponse(
    String paymentId, String status, String mode, String clientSecret, String publishableKey) {}
