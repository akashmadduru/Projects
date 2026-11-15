package com.acme.ecommerce.product.embedding;

import com.acme.ecommerce.common.core.error.ApplicationException;
import com.acme.ecommerce.common.core.error.ErrorCode;

/**
 * An AI-backed operation was requested while the feature is disabled or its provider is down.
 *
 * <p>Public search never throws this - it degrades to keyword results instead. It exists for the
 * operations that have no meaningful degraded answer, such as the admin reindex.
 */
public class AiUnavailableException extends ApplicationException {

  private static final long serialVersionUID = 1L;

  public AiUnavailableException(String message) {
    super(ErrorCode.AI_UNAVAILABLE, message);
  }
}
