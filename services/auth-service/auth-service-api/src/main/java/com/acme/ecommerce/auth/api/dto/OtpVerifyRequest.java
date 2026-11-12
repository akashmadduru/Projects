package com.acme.ecommerce.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST /api/auth/otp/verify. */
public record OtpVerifyRequest(
    @NotBlank @Size(max = 20) String phone, @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {

  @Override
  public String toString() {
    return "OtpVerifyRequest[phone=" + phone + ", code=***]";
  }
}
