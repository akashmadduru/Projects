package com.acme.ecommerce.auth.api.dto;

import java.util.List;

public record RecoveryCodesResponse(List<String> recoveryCodes) {

  @Override
  public String toString() {
    return "RecoveryCodesResponse[***]";
  }
}
