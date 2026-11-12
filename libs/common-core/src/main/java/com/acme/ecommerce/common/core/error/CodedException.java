package com.acme.ecommerce.common.core.error;

/**
 * Generic platform exception for failure modes that have their own {@link ErrorCode} but no
 * dedicated exception type (e.g. {@code CART_EMPTY}, {@code ADDRESS_LIMIT}). The web layer renders
 * it exactly like any other {@link ApplicationException}.
 */
public class CodedException extends ApplicationException {

  private static final long serialVersionUID = 1L;

  public CodedException(ErrorCode errorCode, String message) {
    super(errorCode, message);
  }

  public CodedException(ErrorCode errorCode, String message, Throwable cause) {
    super(errorCode, message, cause);
  }
}
