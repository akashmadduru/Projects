package com.acme.ecommerce.auth.api.dto;

/** The QR payload is rendered client-side from {@code otpauthUrl}. */
public record TwoFactorSetupResponse(String otpauthUrl, String secretBase32) {

  @Override
  public String toString() {
    return "TwoFactorSetupResponse[***]";
  }
}
