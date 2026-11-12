package com.acme.ecommerce.auth.api;

import java.util.Locale;

/**
 * Auth-specific failure modes, surfaced as the {@code code} member of the RFC 9457 problem
 * document.
 *
 * <p>WHY THIS IS NOT PART OF {@code com.acme.ecommerce.common.core.error.ErrorCode}: that enum is
 * the platform-wide catalogue every service shares. Auth semantics like "the refresh token you
 * presented had already been rotated 40 seconds ago" are meaningless to product-service, and
 * pushing them into the shared enum would make every service recompile whenever auth-service grows
 * a new failure mode. This enum is published in the CONTRACT module instead, so the admin SPA and
 * the gateway can branch on the exact code without depending on auth-service's implementation.
 *
 * <p>The {@code type} URI prefix intentionally matches the one used by {@code ErrorCode} so that
 * every problem document in the platform, whatever produced it, has a consistent {@code type}
 * namespace.
 *
 * <p>CONTRACT STABILITY: once released, a constant's {@link #code()} string must never be renamed.
 * Add a new constant instead.
 *
 * <p>DESIGN NOTE ON THE 401s: {@link #AUTH_INVALID_CREDENTIALS} is returned identically for "no
 * such user" and "wrong password". Distinguishing them would turn the login endpoint into a user
 * directory. {@link #AUTH_ACCOUNT_LOCKED} does reveal that an account exists, which is the accepted
 * trade-off for telling a real user why they cannot get in - and the lockout is only reachable
 * after several failures against a real account anyway.
 */
public enum AuthErrorCode {

  /** Wrong password, or no such user. Deliberately indistinguishable. */
  AUTH_INVALID_CREDENTIALS(401, "Invalid credentials"),

  /** Too many consecutive failures; the account is temporarily locked. */
  AUTH_ACCOUNT_LOCKED(401, "Account locked"),

  /** The account exists but has been administratively disabled. */
  AUTH_ACCOUNT_DISABLED(401, "Account disabled"),

  /** No refresh cookie, unknown token hash, or an expired token. No side effects. */
  AUTH_REFRESH_INVALID(401, "Refresh token invalid"),

  /**
   * A refresh token was presented after it had already been rotated, outside the race-tolerance
   * window. Treated as a stolen token: the ENTIRE token family is revoked.
   */
  AUTH_REFRESH_REUSE_DETECTED(401, "Refresh token reuse detected"),

  /**
   * Two requests raced to rotate the same refresh token and this one lost, or the token was rotated
   * moments ago by a legitimate concurrent client (two browser tabs, an automatic retry). The
   * family is NOT revoked; the client simply retries with the cookie it now holds.
   */
  AUTH_REFRESH_RACE(401, "Refresh token already rotated"),

  /**
   * Google asserted {@code email_verified=false}. Linking or provisioning on an unverified address
   * would let anyone who can sign up at an IdP with a victim's address take over the local account.
   */
  AUTH_OIDC_EMAIL_UNVERIFIED(403, "Identity provider email is not verified"),

  /** Self-service registration is switched off for this deployment. */
  AUTH_REGISTRATION_DISABLED(403, "Registration is disabled"),

  /** An account already exists for this email address. */
  AUTH_EMAIL_TAKEN(409, "Email address already registered"),

  /** The OAuth2 redirect target is not on the configured allowlist. */
  AUTH_OIDC_REDIRECT_NOT_ALLOWED(400, "Redirect target is not allowed"),

  /** The OAuth2 login could not be completed (state cookie missing/tampered, IdP error). */
  AUTH_OIDC_LOGIN_FAILED(401, "Federated login failed"),

  /** Per-IP request throttle tripped. */
  AUTH_RATE_LIMITED(429, "Too many requests"),

  /**
   * A Bearer token was missing, malformed, expired, or failed signature/issuer/audience validation.
   *
   * <p>Distinct from {@link #AUTH_INVALID_CREDENTIALS}, which is about a password. Produced by the
   * resource-server filter chain rather than by a controller.
   */
  AUTH_TOKEN_INVALID(401, "Access token invalid"),

  /** The caller is authenticated but lacks the authority for this operation. */
  AUTH_FORBIDDEN(403, "Forbidden"),

  /** Password-reset token is unknown, expired or already used. */
  AUTH_RESET_TOKEN_INVALID(400, "Reset token invalid"),

  /** Email-verification token is unknown, expired or already used. */
  AUTH_VERIFY_TOKEN_INVALID(400, "Verification token invalid"),

  /** One-time code is wrong, expired, or its attempts are exhausted. */
  AUTH_OTP_INVALID(401, "Verification code invalid"),

  /** The phone number is not valid E.164. */
  AUTH_PHONE_INVALID(400, "Phone number invalid"),

  /** The phone number is already verified on another account. */
  AUTH_PHONE_TAKEN(409, "Phone number already in use");

  private static final String TYPE_PREFIX = "https://errors.acme-ecommerce.internal/";

  private final int status;
  private final String title;

  AuthErrorCode(int status, String title) {
    this.status = status;
    this.title = title;
  }

  /** Stable machine-readable code; identical to the constant name. */
  public String code() {
    return name();
  }

  /** HTTP status this failure maps to. */
  public int status() {
    return status;
  }

  /** Short human-readable summary; the RFC 9457 {@code title} member. */
  public String title() {
    return title;
  }

  /** Stable problem-type URI; the RFC 9457 {@code type} member. */
  public String type() {
    return TYPE_PREFIX + name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
