package com.acme.ecommerce.payment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Internal refund. {@code amount} null = everything not yet refunded. Idempotent by key. */
public record RefundRequest(
    @Positive BigDecimal amount,
    @Size(max = 200) String reason,
    @NotBlank @Size(max = 100) String idempotencyKey) {}
