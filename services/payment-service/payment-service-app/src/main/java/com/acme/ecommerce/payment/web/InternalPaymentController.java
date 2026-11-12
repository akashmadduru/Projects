package com.acme.ecommerce.payment.web;

import com.acme.ecommerce.payment.api.PaymentClient;
import com.acme.ecommerce.payment.api.dto.CreatePaymentRequest;
import com.acme.ecommerce.payment.api.dto.PaymentResponse;
import com.acme.ecommerce.payment.api.dto.RefundRequest;
import com.acme.ecommerce.payment.api.dto.RefundResponse;
import com.acme.ecommerce.payment.service.PaymentService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service endpoints; guarded by InternalTokenFilter. Never routed by the gateway. */
@RestController
public class InternalPaymentController implements PaymentClient {

  private final PaymentService service;

  public InternalPaymentController(PaymentService service) {
    this.service = service;
  }

  @Override
  public PaymentResponse create(@Valid @RequestBody CreatePaymentRequest request) {
    return service.create(request);
  }

  @Override
  public PaymentResponse getByOrder(@PathVariable("orderId") UUID orderId) {
    return service.getInternal(orderId);
  }

  @Override
  public RefundResponse refund(
      @PathVariable("orderId") UUID orderId, @Valid @RequestBody RefundRequest request) {
    return service.refund(orderId, request);
  }

  @Override
  public PaymentResponse codCollected(@PathVariable("orderId") UUID orderId) {
    return service.codCollected(orderId);
  }
}
