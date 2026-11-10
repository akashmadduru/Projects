package com.acme.ecommerce.common.core.error;

import java.util.LinkedHashMap;
import java.util.Map;

/** Thrown when an addressed aggregate/entity does not exist, or is not visible to the caller. */
public class ResourceNotFoundException extends ApplicationException {

  private static final long serialVersionUID = 1L;

  public ResourceNotFoundException(String message) {
    super(ErrorCode.RESOURCE_NOT_FOUND, message);
  }

  /**
   * Preferred form: produces a consistent message and attaches the lookup key as an extension
   * member so clients do not have to parse the message text.
   *
   * @param resourceType logical type name, e.g. {@code "Product"}
   * @param identifier the value that was looked up
   */
  public ResourceNotFoundException(String resourceType, Object identifier) {
    super(
        ErrorCode.RESOURCE_NOT_FOUND,
        "%s not found: %s".formatted(resourceType, identifier),
        buildDetails(resourceType, identifier),
        null);
  }

  private static Map<String, Object> buildDetails(String resourceType, Object identifier) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("resourceType", resourceType);
    details.put("identifier", String.valueOf(identifier));
    return details;
  }
}
