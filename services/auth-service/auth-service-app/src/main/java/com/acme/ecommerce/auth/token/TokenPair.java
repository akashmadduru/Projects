package com.acme.ecommerce.auth.token;

import java.time.Instant;

/**
 * The output of {@link TokenService#issueTokenPair}: one short-lived access token and one opaque
 * refresh token.
 *
 * <p>{@code refreshTokenRaw} is the ONLY moment the raw refresh token exists anywhere in this
 * service. It is written straight into a {@code HttpOnly} cookie by the web layer and then
 * forgotten; the database holds only its SHA-256 hash. Nothing may log this record - hence the
 * masking {@link #toString()}.
 *
 * @param accessToken RS256 JWT
 * @param expiresInSeconds access-token lifetime
 * @param refreshTokenRaw opaque 256-bit token, base64url encoded
 * @param refreshExpiresAt sliding expiry of the refresh token, used to size the cookie's Max-Age
 */
public record TokenPair(
    String accessToken, long expiresInSeconds, String refreshTokenRaw, Instant refreshExpiresAt) {

  @Override
  public String toString() {
    return "TokenPair[expiresInSeconds="
        + expiresInSeconds
        + ", refreshExpiresAt="
        + refreshExpiresAt
        + ", accessToken=***, refreshTokenRaw=***]";
  }
}
