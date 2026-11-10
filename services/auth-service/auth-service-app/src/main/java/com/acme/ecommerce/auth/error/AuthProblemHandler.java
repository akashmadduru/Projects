package com.acme.ecommerce.auth.error;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.common.core.correlation.CorrelationIdHolder;
import com.acme.ecommerce.common.core.time.TimeProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns {@link AuthException} into an RFC 9457 problem document carrying the precise {@link
 * AuthErrorCode}.
 *
 * <p>WHY A SECOND ADVICE INSTEAD OF EXTENDING common-web's: {@code GlobalExceptionHandler} derives
 * the {@code code} member from the platform-wide {@code ErrorCode} enum, which knows nothing about
 * refresh-token races. Rather than widen that shared enum with auth-only constants that every other
 * service would then compile against, this advice registers ahead of it (a lower {@code @Order}
 * value wins) and emits the same document SHAPE with a more specific {@code code}. Every other
 * exception in this service still falls through to the shared handler, so there is exactly one
 * error envelope in the platform.
 *
 * <p>LOGGING POLICY: authentication failures are ordinary traffic on an internet-facing endpoint,
 * so they log at WARN with no stack trace. The one exception is {@link
 * AuthErrorCode#AUTH_REFRESH_REUSE_DETECTED}, which is logged by the service that detects it with
 * the full device context, because it is a security incident and not a user error.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 300)
public class AuthProblemHandler {

  private static final Logger log = LoggerFactory.getLogger(AuthProblemHandler.class);

  private final TimeProvider timeProvider;

  public AuthProblemHandler(TimeProvider timeProvider) {
    this.timeProvider = timeProvider;
  }

  /** Translates an auth failure. */
  @ExceptionHandler(AuthException.class)
  public ResponseEntity<ProblemDetail> handleAuthException(
      AuthException exception, HttpServletRequest request) {

    AuthErrorCode code = exception.authErrorCode();
    String path = request == null ? null : request.getRequestURI();

    log.warn("Auth request rejected [{}] on {}: {}", code.code(), path, exception.getMessage());

    return ResponseEntity.status(code.status())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problemDetail(code, path));
  }

  /**
   * Builds the document by hand rather than through {@code ProblemPayloadFactory}, because the
   * factory's {@code code}/{@code type}/{@code title} all come from the shared enum. The MEMBER SET
   * is identical to what common-web produces - type, title, status, detail, instance, code,
   * correlationId, timestamp - so a client parses both with the same code.
   */
  private ProblemDetail problemDetail(AuthErrorCode code, String path) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(code.status()), code.title());
    problem.setType(URI.create(code.type()));
    problem.setTitle(code.title());
    if (path != null) {
      problem.setInstance(URI.create(path));
    }
    problem.setProperty("code", code.code());
    problem.setProperty("correlationId", CorrelationIdHolder.getOrGenerate());
    problem.setProperty("timestamp", timeProvider.now());
    return problem;
  }
}
