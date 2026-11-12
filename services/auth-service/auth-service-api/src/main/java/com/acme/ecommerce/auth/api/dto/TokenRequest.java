package com.acme.ecommerce.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/auth/email/verify. */
public record TokenRequest(@NotBlank @Size(max = 200) String token) {

  @Override
  public String toString() {
    return "TokenRequest[token=***]";
  }
}
