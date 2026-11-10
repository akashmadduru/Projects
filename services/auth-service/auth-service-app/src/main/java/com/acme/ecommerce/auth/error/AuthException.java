package com.acme.ecommerce.auth.error;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.common.core.error.ApplicationException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import java.util.Objects;

/**
 * The one exception type auth-service throws for an expected, client-visible failure.
 *
 * <p>Extends the platform's {@link ApplicationException} so that if it ever escapes past {@link
 * AuthProblemHandler} it still becomes a well-formed problem document rather than a 500 - the
 * {@link ErrorCode} passed to {@code super} is that fallback, and is not what a caller normally
 * sees. {@link AuthProblemHandler} runs first and uses the far more specific {@link AuthErrorCode}.
 *
 * <p>DELIBERATELY NOT A HIERARCHY. A subclass per failure mode would add a dozen types whose only
 * distinguishing feature is a constant, and would invite {@code catch
 * (InvalidCredentialsException)} blocks that quietly change behaviour. The enum carries the
 * variation; the type carries the contract.
 *
 * <p>The {@code message} is for the log. The client-facing text is {@link AuthErrorCode#title()}
 * plus whatever safe detail the handler chooses - never this message, which may name a user id.
 */
public class AuthException extends ApplicationException {

  private static final long serialVersionUID = 1L;

  private final transient AuthErrorCode authErrorCode;

  public AuthException(AuthErrorCode authErrorCode) {
    this(authErrorCode, authErrorCode.title());
  }

  public AuthException(AuthErrorCode authErrorCode, String message) {
    super(fallbackErrorCode(authErrorCode), message);
    this.authErrorCode = Objects.requireNonNull(authErrorCode, "authErrorCode");
  }

  public AuthErrorCode authErrorCode() {
    return authErrorCode;
  }

  /**
   * Maps the auth-specific code onto the nearest platform-wide code.
   *
   * <p>Only ever used if this exception escapes the auth advice - for example when it is thrown
   * from a filter that runs outside MVC. Keeping the mapping here means that path still produces a
   * sane status code instead of a 500.
   */
  private static ErrorCode fallbackErrorCode(AuthErrorCode code) {
    return switch (code.status()) {
      case 400 -> ErrorCode.VALIDATION_FAILED;
      case 403 -> ErrorCode.FORBIDDEN;
      case 409 -> ErrorCode.RESOURCE_CONFLICT;
      case 429 -> ErrorCode.DOMAIN_RULE_VIOLATION;
      default -> ErrorCode.UNAUTHENTICATED;
    };
  }

  /**
   * Exception construction on the login path happens on every failed attempt, and the stack trace
   * is never used - the handler logs a one-line WARN. Suppressing trace capture removes the single
   * most expensive part of throwing, which matters when the endpoint is under a credential-stuffing
   * run.
   */
  @Override
  public synchronized Throwable fillInStackTrace() {
    return this;
  }
}
