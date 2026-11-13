package com.acme.ecommerce.auth.api.dto;

import jakarta.validation.constraints.Size;

public record TwoFactorDisableRequest(
    @Size(max = 10) String code,
    @Size(max = 32) String recoveryCode,
    @Size(max = 200) String password) {

  @Override
  public String toString() {
    return "TwoFactorDisableRequest[***]";
  }
}
