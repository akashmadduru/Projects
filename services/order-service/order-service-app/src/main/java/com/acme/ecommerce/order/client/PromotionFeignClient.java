package com.acme.ecommerce.order.client;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * promotion-service. validate() is the user-facing endpoint (the caller's bearer token is relayed by
 * common-web); redeem/release are internal (X-Internal-Token).
 */
@FeignClient(
    name = "promotion-service",
    contextId = "promotionClient",
    url = "${services.promotion.url:}",
    configuration = InternalFeignConfig.class)
public interface PromotionFeignClient {

  record ValidateRequest(
      String code, BigDecimal subtotal, String currency, Boolean firstOrder) {}

  record Validation(String code, String description, BigDecimal discountAmount, String currency) {}

  record RedeemRequest(
      String code,
      UUID userId,
      UUID orderId,
      BigDecimal subtotal,
      String currency,
      boolean firstOrder) {}

  record Redemption(BigDecimal discountAmount) {}

  @PostMapping("/api/promotions/coupons/validate")
  Validation validate(@RequestBody ValidateRequest request);

  @PostMapping("/internal/promotions/redemptions")
  Redemption redeem(@RequestBody RedeemRequest request);

  @DeleteMapping("/internal/promotions/redemptions/{orderId}")
  void release(@PathVariable("orderId") UUID orderId);
}
