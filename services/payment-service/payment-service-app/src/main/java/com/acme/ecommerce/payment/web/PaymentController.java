package com.acme.ecommerce.payment.web;

import com.acme.ecommerce.payment.api.dto.PaymentConfigResponse;
import com.acme.ecommerce.payment.api.dto.PaymentResponse;
import com.acme.ecommerce.payment.api.dto.SimulatePaymentRequest;
import com.acme.ecommerce.payment.service.PaymentService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public / customer payment endpoints. */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

  private final PaymentService service;

  public PaymentController(PaymentService service) {
    this.service = service;
  }

  @GetMapping("/config")
  public PaymentConfigResponse config() {
    return service.config();
  }

  @GetMapping("/orders/{orderId}")
  public PaymentResponse byOrder(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
    return service.getForOwner(orderId, UUID.fromString(jwt.getSubject()));
  }

  @PostMapping("/{paymentId}/simulate")
  public PaymentResponse simulate(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID paymentId,
      @Valid @RequestBody SimulatePaymentRequest request) {
    return service.simulate(paymentId, UUID.fromString(jwt.getSubject()), request.outcome());
  }

  /** Raw body (String) so the Stripe signature is verified over the exact bytes sent. */
  @PostMapping("/webhook")
  public ResponseEntity<Void> webhook(
      @RequestBody String payload,
      @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
    service.handleWebhook(payload, signature);
    return ResponseEntity.ok().build();
  }
}
