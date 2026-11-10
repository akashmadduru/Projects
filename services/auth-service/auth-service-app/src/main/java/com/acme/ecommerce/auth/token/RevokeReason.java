package com.acme.ecommerce.auth.token;

/**
 * Why a refresh token stopped being usable.
 *
 * <p>Persisted as {@code name()} into {@code refresh_tokens.revoked_reason}, which carries a CHECK
 * constraint listing exactly these values - so adding a constant here without a migration fails
 * loudly at insert time rather than silently storing something no query filters on.
 *
 * <p>This column is the audit trail. "The user was logged out" and "we revoked their entire session
 * family because someone replayed a token" look identical without it.
 */
public enum RevokeReason {

  /** Normal rotation: the token was exchanged for a child. Not a security event. */
  ROTATED,

  /** The user logged out. Revokes the whole family. */
  LOGOUT,

  /** A token was presented after rotation, outside the race window. Revokes the whole family. */
  REUSE_DETECTED,

  /** Collateral revocation of a family member, following REUSE_DETECTED or LOGOUT. */
  FAMILY_REVOKED,

  /** The password changed; every existing session must be re-established. */
  PASSWORD_CHANGE,

  /** An administrator terminated the session. */
  ADMIN,

  /** The token or its family passed its expiry before it was ever rotated. */
  EXPIRED
}
