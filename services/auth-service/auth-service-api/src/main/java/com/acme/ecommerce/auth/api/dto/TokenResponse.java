package com.acme.ecommerce.auth.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The result of any successful authentication or refresh.
 * Refresh token is HttpOnly cookie, access token is in body.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenResponse {
  String accessToken;
  String tokenType;
  long expiresIn;
  AuthMethod authMethod;

  public static final String BEARER = "Bearer";

  public static TokenResponse bearer(String accessToken, long expiresIn, AuthMethod authMethod) {
    return new TokenResponse(accessToken, BEARER, expiresIn, authMethod);
  }

  @Override
  public String toString() {
    return "TokenResponse[tokenType="
        + tokenType
        + ", expiresIn="
        + expiresIn
        + ", authMethod="
        + authMethod
        + ", accessToken=***]";
  }
}
