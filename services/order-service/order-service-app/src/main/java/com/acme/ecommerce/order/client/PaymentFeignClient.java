package com.acme.ecommerce.order.client;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** payment-service internal endpoints (X-Internal-Token). Shapes copied from the Phase 6 contract. */
@FeignClient(
    name = "payment-service",
    contextId = "paymentInternal",
    url = "${services.payment.url:}",
    configuration = InternalFeignConfig.class)
public interface PaymentFeignClient {

  record CreatePayment(
      UUID orderId, UUID userId, BigDecimal amount, String currency, String method) {}

  record PaymentResult(
      String paymentId,
      UUID orderId,
      String status,
      String mode,
      String clientSecret,
      String publishableKey) {}

  record RefundRequest(BigDecimal amount, String reason, String idempotencyKey) {}

  record RefundResult(String status, BigDecimal refundedAmount) {}

  @PostMapping("/internal/payments")
  PaymentResult create(@RequestBody CreatePayment request);

  @PostMapping("/internal/payments/orders/{orderId}/refunds")
  RefundResult refund(@PathVariable("orderId") UUID orderId, @RequestBody RefundRequest request);

  @PostMapping("/internal/payments/orders/{orderId}/cod-collected")
  void codCollected(@PathVariable("orderId") UUID orderId);
}
