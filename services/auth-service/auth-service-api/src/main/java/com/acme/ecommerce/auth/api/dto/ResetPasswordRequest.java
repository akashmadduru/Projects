package com.acme.ecommerce.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/auth/password/reset. The password is never logged. */
public record ResetPasswordRequest(
    @NotBlank @Size(max = 200) String token, @NotBlank @Size(min = 12, max = 200) String newPassword) {

  @Override
  public String toString() {
    return "ResetPasswordRequest[token=***, newPassword=***]";
  }
}
