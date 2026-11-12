package com.acme.ecommerce.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Body of POST /api/auth/me/phone/verify. */
public record CodeRequest(@NotBlank @Pattern(regexp = "[0-9]{6}") String code) {

  @Override
  public String toString() {
    return "CodeRequest[code=***]";
  }
}
