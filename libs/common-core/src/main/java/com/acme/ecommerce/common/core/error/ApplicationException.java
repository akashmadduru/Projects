package com.acme.ecommerce.common.core.error;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Base type for every deliberately-thrown platform exception.
 *
 * <p>Unchecked on purpose: these represent request-scoped outcomes that a caller several frames up
 * (the {@code @RestControllerAdvice}) translates into a response. Forcing intermediate service code
 * to declare or wrap them adds noise without adding safety.
 *
 * <p>Carries the {@link ErrorCode} plus an optional map of extension members that the web layer
 * copies onto the RFC 9457 problem document. Never put anything sensitive in {@code details} - it
 * is serialised to the client verbatim.
 */
public abstract class ApplicationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ErrorCode errorCode;
  private final transient Map<String, Object> details;

  protected ApplicationException(ErrorCode errorCode, String message) {
    this(errorCode, message, Map.of(), null);
  }

  protected ApplicationException(ErrorCode errorCode, String message, Throwable cause) {
    this(errorCode, message, Map.of(), cause);
  }

  protected ApplicationException(
      ErrorCode errorCode, String message, Map<String, Object> details, Throwable cause) {
    super(message, cause);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    this.details = Map.copyOf(Objects.requireNonNullElseGet(details, Map::of));
  }

  public ErrorCode errorCode() {
    return errorCode;
  }

  /** Immutable extension members to attach to the problem document. Never null. */
  public Map<String, Object> details() {
    return details;
  }

  /** Returns a copy of this exception's details plus one extra entry. */
  protected static Map<String, Object> detailsWith(String key, Object value) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(key, value);
    return map;
  }
}
