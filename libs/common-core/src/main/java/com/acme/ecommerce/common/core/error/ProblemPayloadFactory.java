package com.acme.ecommerce.common.core.error;

import com.acme.ecommerce.common.core.correlation.CorrelationIdHolder;
import com.acme.ecommerce.common.core.time.TimeProvider;
import java.util.Map;
import java.util.Objects;

/**
 * Builds {@link ProblemPayload} instances consistently.
 *
 * <p>Centralising construction is what guarantees every error response across every service carries
 * the same members (code, correlationId, timestamp) - which is the whole point of having a shared
 * envelope. It also enforces the rule that 5xx details are never derived from exception messages.
 *
 * <p>Instances are immutable and thread-safe; register one as a singleton bean.
 */
public final class ProblemPayloadFactory {

  /**
   * Fixed text used for any 5xx. Exception messages routinely leak SQL fragments, hostnames and
   * class names; those belong in the log (correlated by id), never in the response body.
   */
  private static final String OPAQUE_SERVER_ERROR_DETAIL =
      "The server encountered an unexpected error. "
          + "Quote the correlationId when contacting support.";

  private final TimeProvider timeProvider;

  public ProblemPayloadFactory(TimeProvider timeProvider) {
    this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
  }

  /** Builds a payload from a platform exception, preserving its extension members. */
  public ProblemPayload from(ApplicationException exception, String instance) {
    Objects.requireNonNull(exception, "exception");
    return create(exception.errorCode(), exception.getMessage(), instance, exception.details());
  }

  /** Builds a payload for an arbitrary error code with an explicit detail message. */
  public ProblemPayload create(ErrorCode errorCode, String detail, String instance) {
    return create(errorCode, detail, instance, Map.of());
  }

  /** Builds a payload for an arbitrary error code with extension members. */
  public ProblemPayload create(
      ErrorCode errorCode, String detail, String instance, Map<String, Object> extensions) {
    Objects.requireNonNull(errorCode, "errorCode");
    return new ProblemPayload(
        errorCode.type(),
        errorCode.title(),
        errorCode.status(),
        safeDetail(errorCode, detail),
        instance,
        errorCode.code(),
        CorrelationIdHolder.getOrGenerate(),
        timeProvider.now(),
        extensions);
  }

  /**
   * Builds the payload for an unhandled throwable. The cause is intentionally not consulted for the
   * detail text.
   */
  public ProblemPayload internalError(String instance) {
    return create(ErrorCode.INTERNAL_ERROR, null, instance, Map.of());
  }

  private static String safeDetail(ErrorCode errorCode, String detail) {
    if (errorCode.status() >= 500) {
      return OPAQUE_SERVER_ERROR_DETAIL;
    }
    return detail == null || detail.isBlank() ? errorCode.title() : detail;
  }
}
