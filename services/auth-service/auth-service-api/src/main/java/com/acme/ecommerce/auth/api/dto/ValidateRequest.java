package com.acme.ecommerce.auth.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Introspection request for POST /api/auth/validate. Token is optional - null means use caller's
 * bearer credential.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ValidateRequest {
  String token;

  @Override
  public String toString() {
    return "ValidateRequest[token=" + (token == null ? "null" : "***") + "]";
  }
}
