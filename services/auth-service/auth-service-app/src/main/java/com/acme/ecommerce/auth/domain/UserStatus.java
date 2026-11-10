package com.acme.ecommerce.auth.domain;

/**
 * Lifecycle state of an identity.
 *
 * <p>Persisted as its {@code name()} into a {@code varchar(20)} with a CHECK constraint, not as an
 * ordinal: an ordinal makes reordering the enum a silent data-corruption bug, and makes the table
 * unreadable in psql.
 */
public enum UserStatus {

  /** May authenticate. */
  ACTIVE,

  /** Administratively switched off. Authentication is refused with AUTH_ACCOUNT_DISABLED. */
  DISABLED,

  /**
   * Permanently locked by an administrator.
   *
   * <p>NOT the same thing as the transient brute-force lockout, which lives in {@code
   * user_credentials.locked_until} and expires on its own. Keeping them separate means an automatic
   * lockout can never leave an account stuck in a state only a human can clear.
   */
  LOCKED
}
