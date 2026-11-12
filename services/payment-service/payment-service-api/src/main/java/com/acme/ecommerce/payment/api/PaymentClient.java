package com.acme.ecommerce.payment.api;

import com.acme.ecommerce.payment.api.dto.CreatePaymentRequest;
import com.acme.ecommerce.payment.api.dto.PaymentResponse;
import com.acme.ecommerce.payment.api.dto.RefundRequest;
import com.acme.ecommerce.payment.api.dto.RefundResponse;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Declarative client for the INTERNAL endpoints order-service calls. Not annotated
 * {@code @FeignClient}: the consumer supplies that and a request interceptor adding {@code
 * X-Internal-Token}.
 */
public interface PaymentClient {

  @PostMapping(
      value = "/internal/payments",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  PaymentResponse create(@RequestBody CreatePaymentRequest request);

  @org.springframework.web.bind.annotation.GetMapping(
      value = "/internal/payments/orders/{orderId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  PaymentResponse getByOrder(@PathVariable("orderId") UUID orderId);

  @PostMapping(
      value = "/internal/payments/orders/{orderId}/refunds",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  RefundResponse refund(@PathVariable("orderId") UUID orderId, @RequestBody RefundRequest request);

  @PostMapping(
      value = "/internal/payments/orders/{orderId}/cod-collected",
      produces = MediaType.APPLICATION_JSON_VALUE)
  PaymentResponse codCollected(@PathVariable("orderId") UUID orderId);
}
