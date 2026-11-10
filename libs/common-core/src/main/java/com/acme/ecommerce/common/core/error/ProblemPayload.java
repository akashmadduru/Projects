package com.acme.ecommerce.common.core.error;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Framework-neutral representation of an RFC 9457 problem document.
 *
 * <p>Deliberately NOT {@code org.springframework.http.ProblemDetail}: common-core must stay free of
 * Spring Web so that non-web modules can depend on it. common-web converts this record into a
 * {@code ProblemDetail} at the HTTP boundary; anything else (a batch job writing a failure record,
 * a message consumer publishing to a DLQ) can consume it directly.
 *
 * @param type stable problem-type URI
 * @param title short human-readable summary
 * @param status HTTP status code
 * @param detail human-readable explanation of this specific occurrence
 * @param instance the request path this occurred on, when known
 * @param code stable machine-readable error code
 * @param correlationId correlation id for cross-service log lookup
 * @param timestamp when the problem was produced
 * @param extensions additional members, copied onto the serialised document
 */
public record ProblemPayload(
    String type,
    String title,
    int status,
    String detail,
    String instance,
    String code,
    String correlationId,
    Instant timestamp,
    Map<String, Object> extensions) {

  public ProblemPayload {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(code, "code");
    extensions = Map.copyOf(Objects.requireNonNullElseGet(extensions, Map::of));
  }
}
