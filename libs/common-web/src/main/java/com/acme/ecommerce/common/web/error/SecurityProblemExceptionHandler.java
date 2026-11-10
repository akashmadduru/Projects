package com.acme.ecommerce.common.web.error;

import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.core.error.ProblemPayloadFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Problem+json translation for Spring Security exceptions that escape into MVC.
 *
 * <p>Kept separate from {@link GlobalExceptionHandler} on purpose. Spring resolves the types named
 * in {@code @ExceptionHandler} when it registers an advice bean, so an advice that mentions {@code
 * AccessDeniedException} blows up with {@code NoClassDefFoundError} in any application that does
 * not have spring-security on the classpath. Isolating them here lets the auto-configuration
 * register this advice only when security is actually present.
 *
 * <p>SCOPE: this only covers exceptions thrown from <em>method security</em> (i.e. after the
 * request has reached a controller). Rejections inside the security filter chain never reach MVC -
 * those are produced by the chain's {@code AuthenticationEntryPoint} / {@code AccessDeniedHandler},
 * which each service configures in its own {@code SecurityFilterChain}.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 200)
public class SecurityProblemExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(SecurityProblemExceptionHandler.class);

  private final ProblemPayloadFactory problemPayloadFactory;

  public SecurityProblemExceptionHandler(ProblemPayloadFactory problemPayloadFactory) {
    this.problemPayloadFactory = problemPayloadFactory;
  }

  /** Authenticated caller lacking the required authority. */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleAccessDenied(
      AccessDeniedException exception, HttpServletRequest request) {
    log.warn("Access denied on {}: {}", request.getRequestURI(), exception.getMessage());
    return respond(
        ErrorCode.FORBIDDEN, "You do not have permission to perform this action", request);
  }

  /** Missing or unverifiable credentials. */
  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ProblemDetail> handleAuthentication(
      AuthenticationException exception, HttpServletRequest request) {
    log.warn("Authentication failed on {}: {}", request.getRequestURI(), exception.getMessage());
    return respond(ErrorCode.UNAUTHENTICATED, "Authentication is required", request);
  }

  private ResponseEntity<ProblemDetail> respond(
      ErrorCode errorCode, String detail, HttpServletRequest request) {
    ProblemDetail body =
        GlobalExceptionHandler.toProblemDetail(
            problemPayloadFactory.create(errorCode, detail, request.getRequestURI()));
    return ResponseEntity.status(errorCode.status())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
