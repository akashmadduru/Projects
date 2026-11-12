package com.acme.ecommerce.payment.api.dto;

/** Public config: {@code mode} STRIPE or SIMULATED; publishableKey is null when SIMULATED. */
public record PaymentConfigResponse(String mode, String publishableKey) {}
