package com.acme.ecommerce.cart.api;

import com.acme.ecommerce.cart.api.dto.CartResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Declarative client for the parts of cart-service another service (order-service) may call. Not
 * annotated {@code @FeignClient}: the consumer supplies that. The caller's bearer token is relayed
 * by common-web, so the cart is always the token's own user's cart.
 */
public interface CartClient {

  String BASE_PATH = "/api/cart";

  @GetMapping(value = BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
  CartResponse getCart();

  @DeleteMapping(BASE_PATH)
  void clearCart();
}
