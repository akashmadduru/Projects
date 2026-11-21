package com.acme.ecommerce.cart.web;

import com.acme.ecommerce.cart.api.dto.ConsumedLinesRequest;
import com.acme.ecommerce.cart.api.dto.ConsumedLinesResponse;
import com.acme.ecommerce.cart.service.CartService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service cart endpoints. Protected by the X-Internal-Token chain in {@code
 * InternalSecurityConfig}; the gateway never routes {@code /internal/**}.
 */
@RestController
@RequestMapping("/internal/carts")
public class InternalCartController {

  private final CartService cart;

  public InternalCartController(CartService cart) {
    this.cart = cart;
  }

  /** Removes the lines an order consumed from that user's cart (exact match only). */
  @PostMapping("/{userId}/consumed")
  public ConsumedLinesResponse removeConsumed(
      @PathVariable UUID userId, @Valid @RequestBody ConsumedLinesRequest request) {
    return new ConsumedLinesResponse(cart.removeConsumed(userId, request));
  }
}
