package com.acme.ecommerce.common.web.error;

import com.acme.ecommerce.common.core.error.ApplicationException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.core.error.ProblemPayload;
import com.acme.ecommerce.common.core.error.ProblemPayloadFactory;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates exceptions into RFC 9457 {@code application/problem+json} responses.
 *
 * <p>This is the platform's ONLY place where an exception becomes an HTTP response body.
 * Controllers must not catch-and-convert; services must not return error DTOs. That single-point
 * rule is what keeps the error envelope identical across every service and every endpoint.
 *
 * <p>Ordered {@link Ordered#LOWEST_PRECEDENCE} minus a small margin so a service can register its
 * own more-specific advice ahead of it without having to disable this one.
 *
 * <p>SECURITY NOTE: 5xx responses never echo the exception message ({@link ProblemPayloadFactory}
 * enforces this). The stack trace goes to the log, keyed by the same correlationId returned to the
 * caller.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final ProblemPayloadFactory problemPayloadFactory;

  public GlobalExceptionHandler(ProblemPayloadFactory problemPayloadFactory) {
    this.problemPayloadFactory = problemPayloadFactory;
  }

  /** Every deliberately-thrown platform exception. */
  @ExceptionHandler(ApplicationException.class)
  public ResponseEntity<ProblemDetail> handleApplicationException(
      ApplicationException exception, HttpServletRequest request) {

    ProblemPayload payload = problemPayloadFactory.from(exception, path(request));

    // 4xx is the caller's problem and is expected traffic - log at WARN without a stack trace to
    // avoid drowning the log. 5xx is ours - log the full trace at ERROR.
    if (payload.status() >= 500) {
      log.error("Request failed [{}]: {}", payload.code(), exception.getMessage(), exception);
    } else {
      log.warn("Request rejected [{}]: {}", payload.code(), exception.getMessage());
    }
    return toResponse(payload);
  }

  /** Bean-validation failures on {@code @Valid @RequestBody} arguments. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exception, HttpServletRequest request) {

    List<Map<String, Object>> fieldErrors =
        exception.getBindingResult().getFieldErrors().stream()
            .map(
                error -> {
                  Map<String, Object> entry = new LinkedHashMap<>();
                  entry.put("field", error.getField());
                  entry.put(
                      "message",
                      error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage());
                  return entry;
                })
            .toList();

    ProblemPayload payload =
        problemPayloadFactory.create(
            ErrorCode.VALIDATION_FAILED,
            "Request validation failed",
            path(request),
            Map.of("errors", fieldErrors));

    log.warn("Validation failed on {}: {}", path(request), fieldErrors);
    return toResponse(payload);
  }

  /** Missing required query parameter. */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ProblemDetail> handleMissingParameter(
      MissingServletRequestParameterException exception, HttpServletRequest request) {
    return badRequest(
        "Required parameter '%s' is missing".formatted(exception.getParameterName()), request);
  }

  /** Path variable / query parameter that could not be coerced to the declared type. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ProblemDetail> handleTypeMismatch(
      MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
    return badRequest(
        "Parameter '%s' has an invalid value".formatted(exception.getName()), request);
  }

  /**
   * Malformed or unparseable request body.
   *
   * <p>The exception message is NOT echoed: it can contain a fragment of the raw payload and the
   * fully-qualified target class name.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ProblemDetail> handleUnreadableBody(
      HttpMessageNotReadableException exception, HttpServletRequest request) {
    log.warn("Unreadable request body on {}: {}", path(request), exception.getMessage());
    return badRequest("Request body is missing or malformed", request);
  }

  // NOTE: Spring Security's AccessDeniedException / AuthenticationException are handled by
  // SecurityProblemExceptionHandler, a separate advice. They are deliberately NOT handled here:
  // Spring resolves @ExceptionHandler annotation attribute types when it registers the advice, so
  // referencing security types from this class would throw NoClassDefFoundError in any service that
  // includes common-web without spring-security on the classpath.

  /** Unknown route. Requires {@code spring.mvc.throw-exception-if-no-handler-found=true}. */
  @ExceptionHandler(NoHandlerFoundException.class)
  public ResponseEntity<ProblemDetail> handleNoHandlerFound(
      NoHandlerFoundException exception, HttpServletRequest request) {
    return toResponse(
        problemPayloadFactory.create(
            ErrorCode.RESOURCE_NOT_FOUND, "No handler for this request", path(request)));
  }

  /**
   * Unknown route, Spring 6.1+ form.
   *
   * <p>MUST BE HANDLED SEPARATELY FROM {@link NoHandlerFoundException}. Since Spring Framework 6.1
   * an unmatched path falls through to the static-resource handler, which raises {@code
   * NoResourceFoundException} instead - a different type, in a different package, that is NOT a
   * subclass of the old one. Without this method the request lands on the catch-all {@code
   * Exception} handler below and every typo in a URL is reported as a 500, which turns "the client
   * called the wrong path" into what looks like a server fault (and, on a dashboard, into a
   * spurious error-rate spike).
   *
   * <p>The requested path is deliberately not echoed into the detail message: on a public endpoint
   * that reflects arbitrary caller input straight back into a response body.
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ProblemDetail> handleNoResourceFound(
      NoResourceFoundException exception, HttpServletRequest request) {
    return toResponse(
        problemPayloadFactory.create(
            ErrorCode.RESOURCE_NOT_FOUND, "No handler for this request", path(request)));
  }

  /** Wrong HTTP method for an existing route. */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {

    ProblemPayload payload =
        problemPayloadFactory.create(
            ErrorCode.VALIDATION_FAILED,
            "HTTP method '%s' is not supported for this endpoint".formatted(exception.getMethod()),
            path(request));

    ProblemDetail body = toProblemDetail(payload);
    body.setStatus(HttpStatus.METHOD_NOT_ALLOWED.value());
    body.setTitle("Method not allowed");
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }

  /**
   * Catch-all. Anything reaching here is an unanticipated defect, so it is logged at ERROR with the
   * full trace and returned as an opaque 500.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(
      Exception exception, HttpServletRequest request) {
    ProblemPayload payload = problemPayloadFactory.internalError(path(request));
    log.error(
        "Unhandled exception on {} [correlationId={}]",
        path(request),
        payload.correlationId(),
        exception);
    return toResponse(payload);
  }

  // --- helpers ---------------------------------------------------------------

  private ResponseEntity<ProblemDetail> badRequest(String detail, HttpServletRequest request) {
    return toResponse(
        problemPayloadFactory.create(ErrorCode.VALIDATION_FAILED, detail, path(request)));
  }

  private ResponseEntity<ProblemDetail> toResponse(ProblemPayload payload) {
    return ResponseEntity.status(payload.status())
        .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
        .body(toProblemDetail(payload));
  }

  /** Converts the framework-neutral payload into Spring's RFC 9457 representation. */
  static ProblemDetail toProblemDetail(ProblemPayload payload) {
    ProblemDetail problemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.valueOf(payload.status()), payload.detail() == null ? "" : payload.detail());
    problemDetail.setType(URI.create(payload.type()));
    problemDetail.setTitle(payload.title());
    if (payload.instance() != null) {
      problemDetail.setInstance(URI.create(payload.instance()));
    }
    problemDetail.setProperty("code", payload.code());
    problemDetail.setProperty("correlationId", payload.correlationId());
    problemDetail.setProperty("timestamp", payload.timestamp());
    payload.extensions().forEach(problemDetail::setProperty);
    return problemDetail;
  }

  private static String path(HttpServletRequest request) {
    return request == null ? null : request.getRequestURI();
  }
}
