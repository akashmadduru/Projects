package com.acme.ecommerce.order.web;

import com.acme.ecommerce.order.service.OrderLifecycleService;
import com.acme.ecommerce.order.service.OrderLifecycleService.PaymentResultOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service only: never routed by the gateway, guarded by X-Internal-Token. */
@RestController
@RequestMapping("/internal/orders")
public class InternalOrderController {

  public record PaymentResultRequest(@NotBlank String status, String paymentId) {}

  private final OrderLifecycleService lifecycle;

  public InternalOrderController(OrderLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  @PostMapping("/{id}/payment-result")
  public PaymentResultOutcome paymentResult(
      @PathVariable UUID id, @Valid @RequestBody PaymentResultRequest request) {
    return lifecycle.paymentResult(id, request.status(), request.paymentId());
  }
}
