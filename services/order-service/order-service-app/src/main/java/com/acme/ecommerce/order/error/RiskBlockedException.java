package com.acme.ecommerce.order.error;

import com.acme.ecommerce.common.core.error.ApplicationException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import java.util.Map;

/** 422 PAYMENT_METHOD_UNAVAILABLE carrying {@code reason: RISK} (fraud score too high for COD). */
public class RiskBlockedException extends ApplicationException {

  private static final long serialVersionUID = 1L;

  public RiskBlockedException(String message) {
    super(ErrorCode.PAYMENT_METHOD_UNAVAILABLE, message, Map.of("reason", "RISK"), null);
  }
}
