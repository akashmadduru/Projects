package com.acme.ecommerce.promotion.api;

import com.acme.ecommerce.promotion.api.dto.CouponValidationResponse;
import com.acme.ecommerce.promotion.api.dto.RedeemRequest;
import com.acme.ecommerce.promotion.api.dto.RedeemResponse;
import com.acme.ecommerce.promotion.api.dto.ValidateCouponRequest;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Declarative client for what order-service calls. Not annotated {@code @FeignClient}: the consumer
 * supplies that. {@code validate} is a user endpoint (caller's bearer relayed by common-web); the
 * redemption calls are INTERNAL and need an {@code X-Internal-Token} interceptor.
 */
public interface PromotionClient {

  @PostMapping(
      value = "/api/promotions/coupons/validate",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  CouponValidationResponse validate(@RequestBody ValidateCouponRequest request);

  @PostMapping(
      value = "/internal/promotions/redemptions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  RedeemResponse redeem(@RequestBody RedeemRequest request);

  @DeleteMapping("/internal/promotions/redemptions/{orderId}")
  void release(@PathVariable("orderId") UUID orderId);
}
