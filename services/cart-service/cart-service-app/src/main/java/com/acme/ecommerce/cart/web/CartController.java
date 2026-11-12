package com.acme.ecommerce.cart.web;

import com.acme.ecommerce.cart.api.CartClient;
import com.acme.ecommerce.cart.api.dto.CartItemRequest;
import com.acme.ecommerce.cart.api.dto.CartResponse;
import com.acme.ecommerce.cart.api.dto.MergeCartRequest;
import com.acme.ecommerce.cart.api.dto.UpdateQuantityRequest;
import com.acme.ecommerce.cart.service.CartService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The calling user own cart. The user id is ALWAYS the JWT subject. */
@RestController
@RequestMapping(CartClient.BASE_PATH)
public class CartController {

  private final CartService cart;

  public CartController(CartService cart) {
    this.cart = cart;
  }

  @GetMapping
  public CartResponse get(@AuthenticationPrincipal Jwt jwt) {
    return cart.get(userId(jwt));
  }

  @PostMapping("/items")
  public CartResponse add(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CartItemRequest request) {
    return cart.addItem(userId(jwt), request);
  }

  @PutMapping("/items/{productId}")
  public CartResponse setQuantity(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID productId,
      @Valid @RequestBody UpdateQuantityRequest request) {
    return cart.setQuantity(userId(jwt), productId, request.quantity());
  }

  @DeleteMapping("/items/{productId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
    cart.removeItem(userId(jwt), productId);
  }

  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void clear(@AuthenticationPrincipal Jwt jwt) {
    cart.clear(userId(jwt));
  }

  @PostMapping("/merge")
  public CartResponse merge(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MergeCartRequest request) {
    return cart.merge(userId(jwt), request.items());
  }

  @PostMapping("/items/{productId}/save-for-later")
  public CartResponse saveForLater(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
    return cart.saveForLater(userId(jwt), productId);
  }

  @PostMapping("/saved/{productId}/move-to-cart")
  public CartResponse moveToCart(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
    return cart.moveSavedToCart(userId(jwt), productId);
  }

  @DeleteMapping("/saved/{productId}")
  public CartResponse removeSaved(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
    return cart.removeSaved(userId(jwt), productId);
  }

  private static UUID userId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
