package com.acme.ecommerce.auth.service;

import com.acme.ecommerce.auth.token.TokenPair;

/** Outcome of a first-factor success: either tokens, or a pending MFA challenge. */
public record LoginResult(TokenPair tokens, String mfaToken, long mfaExpiresInSeconds) {

  public static LoginResult issued(TokenPair pair) {
    return new LoginResult(pair, null, 0);
  }

  public static LoginResult mfa(String mfaToken, long expiresInSeconds) {
    return new LoginResult(null, mfaToken, expiresInSeconds);
  }

  public boolean mfaRequired() {
    return mfaToken != null;
  }
}
