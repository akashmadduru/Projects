package com.acme.ecommerce.payment.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Internal: a caller asks for a payment record. Idempotent by subject id. {@code purpose} defaults
 * to ORDER; the subject is {@code referenceId}, falling back to {@code orderId} (backwards
 * compatible with Phase 6 callers).
 */
public record CreatePaymentRequest(
    UUID orderId,
    @NotNull UUID userId,
    @NotNull @Positive BigDecimal amount,
    @NotNull @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
    @NotNull @Pattern(regexp = "^(CARD|COD)$") String method,
    @Pattern(regexp = "^(ORDER|MEMBERSHIP|GIFT_CARD)$") String purpose,
    UUID referenceId) {

  /** Subject id the payment is keyed by. */
  public UUID subjectId() {
    return referenceId != null ? referenceId : orderId;
  }

  public String effectivePurpose() {
    return purpose == null || purpose.isBlank() ? "ORDER" : purpose;
  }
}
