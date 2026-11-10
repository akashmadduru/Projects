package com.acme.ecommerce.auth.api.dto;

import java.util.Locale;

/**
 * How a principal proved its identity for the session a token belongs to.
 *
 * <p>Carried verbatim in the access token's {@code auth_method} claim and stored on every refresh
 * token row, so a downstream service can make policy decisions that depend on it (for example:
 * require a password re-authentication before a destructive admin action, even though the caller
 * holds a perfectly valid Google-issued session).
 *
 * <p>The wire form is the lower-case {@link #claimValue()}, NOT the enum constant name. Renaming a
 * claim value is a breaking change for every token consumer.
 */
public enum AuthMethod {

  /** Local email + password, verified against the Argon2id hash in {@code user_credentials}. */
  PASSWORD("password"),

  /** Google OIDC. The user may have no local credential at all. */
  GOOGLE("google");

  private final String claimValue;

  AuthMethod(String claimValue) {
    this.claimValue = claimValue;
  }

  /** The value that appears in the {@code auth_method} JWT claim and in the database. */
  public String claimValue() {
    return claimValue;
  }

  /**
   * Parses a wire/database value back into the enum.
   *
   * @throws IllegalArgumentException if the value matches no known method
   */
  public static AuthMethod fromClaimValue(String value) {
    if (value != null) {
      String normalised = value.trim().toLowerCase(Locale.ROOT);
      for (AuthMethod method : values()) {
        if (method.claimValue.equals(normalised)) {
          return method;
        }
      }
    }
    throw new IllegalArgumentException("Unknown auth method: " + value);
  }
}
