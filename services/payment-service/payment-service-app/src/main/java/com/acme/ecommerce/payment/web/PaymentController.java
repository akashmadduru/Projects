package com.acme.ecommerce.payment.web;

import com.acme.ecommerce.payment.api.dto.PaymentConfigResponse;
import com.acme.ecommerce.payment.api.dto.PaymentResponse;
import com.acme.ecommerce.payment.api.dto.RazorpayVerifyRequest;
import com.acme.ecommerce.payment.api.dto.SimulatePaymentRequest;
import com.acme.ecommerce.payment.api.dto.SwitchProviderRequest;
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

  /** The customer picks another provider for a payment that is still unpaid. */
  @PostMapping("/orders/{orderId}/provider")
  public PaymentResponse switchProvider(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID orderId,
      @Valid @RequestBody SwitchProviderRequest request) {
    return service.switchProvider(orderId, UUID.fromString(jwt.getSubject()), request.provider());
  }

  /** Razorpay Checkout finished in the browser: verify its signature server side. */
  @PostMapping("/{paymentId}/razorpay/verify")
  public PaymentResponse verifyRazorpay(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID paymentId,
      @Valid @RequestBody RazorpayVerifyRequest request) {
    return service.verifyRazorpay(
        paymentId,
        UUID.fromString(jwt.getSubject()),
        request.razorpayOrderId(),
        request.razorpayPaymentId(),
        request.razorpaySignature());
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

  /** Raw bytes, decoded as UTF-8 here, so the signature is verified over exactly what was sent. */
  @PostMapping("/webhook/razorpay")
  public ResponseEntity<Void> razorpayWebhook(
      @RequestBody byte[] payload,
      @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
      @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
    service.handleRazorpayWebhook(
        new String(payload, java.nio.charset.StandardCharsets.UTF_8), signature, eventId);
    return ResponseEntity.ok().build();
  }
}
