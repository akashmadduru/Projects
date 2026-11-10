package com.acme.ecommerce.common.core.error;

import java.util.List;
import java.util.Map;

/**
 * Thrown for validation failures detected outside the bean-validation pass - typically cross-field
 * or context-dependent checks a service performs by hand.
 *
 * <p>Bean-validation failures raised by Spring are translated directly by the web layer's advice
 * and do not travel through this type.
 */
public class ValidationException extends ApplicationException {

  private static final long serialVersionUID = 1L;

  public ValidationException(String message) {
    super(ErrorCode.VALIDATION_FAILED, message);
  }

  /**
   * @param fieldErrors field name -> human-readable reason
   */
  public ValidationException(String message, Map<String, String> fieldErrors) {
    super(
        ErrorCode.VALIDATION_FAILED,
        message,
        detailsWith(
            "errors",
            fieldErrors.entrySet().stream()
                .map(e -> Map.<String, Object>of("field", e.getKey(), "message", e.getValue()))
                .toList()),
        null);
  }

  /** Convenience for a single field. */
  public static ValidationException forField(String field, String reason) {
    return new ValidationException(
        "Validation failed for '%s'".formatted(field), Map.of(field, reason));
  }

  /** Returns the structured field errors, or an empty list if none were supplied. */
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> fieldErrors() {
    Object errors = details().get("errors");
    return errors instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }
}
