package com.acme.ecommerce.order.api;

import com.acme.ecommerce.order.api.dto.PurchaseStatusResponse;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Declarative client for the purchase-verification endpoint other services (rating-service) may
 * call. Not annotated {@code @FeignClient}: the consumer supplies that. The caller's bearer token
 * is relayed by common-web, so the answer is always about the token's own user.
 */
public interface OrderPurchaseClient {

  String BASE_PATH = "/api/orders/purchases";

  @GetMapping(value = BASE_PATH + "/{productId}", produces = MediaType.APPLICATION_JSON_VALUE)
  PurchaseStatusResponse purchaseStatus(@PathVariable("productId") UUID productId);
}
