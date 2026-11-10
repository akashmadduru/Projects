package com.acme.ecommerce.common.core.error;

/** Thrown when an authenticated caller lacks the authority required for an operation. */
public class ForbiddenException extends ApplicationException {

  private static final long serialVersionUID = 1L;

  public ForbiddenException(String message) {
    super(ErrorCode.FORBIDDEN, message);
  }
}
