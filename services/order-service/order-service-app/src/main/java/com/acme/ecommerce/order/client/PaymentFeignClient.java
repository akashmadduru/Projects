package com.acme.ecommerce.order.client;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * payment-service internal endpoints (X-Internal-Token). Shapes copied from the Phase 6 contract.
 */
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

  record RefundRequest(
      BigDecimal amount, String reason, String idempotencyKey, String destination) {}

  record WalletBalance(BigDecimal balance, String currency) {}

  record WalletDebit(
      UUID userId, BigDecimal amount, String currency, UUID orderId, String idempotencyKey) {}

  record WalletCredit(
      UUID userId,
      BigDecimal amount,
      String currency,
      String reason,
      String referenceId,
      String idempotencyKey) {}

  record RefundResult(String status, BigDecimal refundedAmount) {}

  /** Give back the debit with debitKey only if it happened (TD-P8-23). */
  record WalletReverse(UUID userId, String debitKey, String creditKey) {}

  record WalletReverseResult(boolean reversed) {}

  @PostMapping("/internal/wallet/reverse")
  WalletReverseResult walletReverse(@RequestBody WalletReverse request);

  @PostMapping("/internal/payments")
  PaymentResult create(@RequestBody CreatePayment request);

  @PostMapping("/internal/payments/orders/{orderId}/refunds")
  RefundResult refund(@PathVariable("orderId") UUID orderId, @RequestBody RefundRequest request);

  @org.springframework.web.bind.annotation.GetMapping("/internal/wallet/{userId}")
  WalletBalance walletBalance(@PathVariable("userId") UUID userId);

  @PostMapping("/internal/wallet/debit")
  WalletBalance walletDebit(@RequestBody WalletDebit request);

  @PostMapping("/internal/wallet/credit")
  WalletBalance walletCredit(@RequestBody WalletCredit request);

  @PostMapping("/internal/payments/orders/{orderId}/cod-collected")
  void codCollected(@PathVariable("orderId") UUID orderId);

  /** Order cancelled or expired before payment: stop the payment from being taken (TD-P6-03). */
  @PostMapping("/internal/payments/orders/{orderId}/cancel")
  void cancelUnpaid(@PathVariable("orderId") UUID orderId);
}
