package com.acme.ecommerce.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Exactly one of {@code code} (TOTP) or {@code recoveryCode} must be present. */
public record MfaVerifyRequest(
    @NotBlank @Size(max = 200) String mfaToken,
    @Size(max = 10) String code,
    @Size(max = 32) String recoveryCode) {

  @Override
  public String toString() {
    return "MfaVerifyRequest[mfaToken=***, code=***, recoveryCode=***]";
  }
}
