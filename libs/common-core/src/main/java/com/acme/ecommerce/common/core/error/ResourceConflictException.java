package com.acme.ecommerce.common.core.error;

/**
 * Thrown when a write cannot be applied because it conflicts with current state: a
 * unique-constraint clash, a duplicate create, or a lost optimistic-locking race.
 */
public class ResourceConflictException extends ApplicationException {

  private static final long serialVersionUID = 1L;

  public ResourceConflictException(String message) {
    super(ErrorCode.RESOURCE_CONFLICT, message);
  }

  public ResourceConflictException(String message, Throwable cause) {
    super(ErrorCode.RESOURCE_CONFLICT, message, cause);
  }
}
