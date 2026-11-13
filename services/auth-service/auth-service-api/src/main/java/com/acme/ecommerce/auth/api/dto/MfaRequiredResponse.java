package com.acme.ecommerce.auth.api.dto;

/** Returned (HTTP 200) instead of a TokenResponse when the account has two-factor auth enabled. */
public record MfaRequiredResponse(boolean mfaRequired, String mfaToken, long expiresIn) {

  public static MfaRequiredResponse of(String mfaToken, long expiresIn) {
    return new MfaRequiredResponse(true, mfaToken, expiresIn);
  }

  @Override
  public String toString() {
    return "MfaRequiredResponse[mfaRequired=true, mfaToken=***]";
  }
}
