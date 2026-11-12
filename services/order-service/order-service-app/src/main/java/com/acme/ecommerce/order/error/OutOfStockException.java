package com.acme.ecommerce.order.error;

import com.acme.ecommerce.common.core.error.ApplicationException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import java.util.List;
import java.util.Map;

/** 409 OUT_OF_STOCK with {@code items:[{productId, requested, available}]} in the problem body. */
public class OutOfStockException extends ApplicationException {

  private static final long serialVersionUID = 1L;

  public OutOfStockException(String message, List<?> items) {
    super(ErrorCode.OUT_OF_STOCK, message, Map.of("items", items), null);
  }
}
