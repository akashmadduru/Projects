package com.acme.ecommerce.cart.web;

import com.acme.ecommerce.cart.api.dto.WishlistItemRequest;
import com.acme.ecommerce.cart.api.dto.WishlistItemResponse;
import com.acme.ecommerce.cart.api.dto.WishlistMergeRequest;
import com.acme.ecommerce.cart.service.WishlistService;
import jakarta.validation.Valid;
import java.util.List;
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

/** The calling user own wishlist. The user id is ALWAYS the JWT subject. */
@RestController
@RequestMapping("/api/wishlist")
public class WishlistController {

  private final WishlistService wishlist;

  public WishlistController(WishlistService wishlist) {
    this.wishlist = wishlist;
  }

  @GetMapping
  public List<WishlistItemResponse> list(@AuthenticationPrincipal Jwt jwt) {
    return wishlist.list(userId(jwt));
  }

  @PutMapping("/{productId}")
  public WishlistItemResponse put(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID productId,
      @Valid @RequestBody WishlistItemRequest request) {
    return wishlist.put(userId(jwt), productId, request);
  }

  @DeleteMapping("/{productId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
    wishlist.remove(userId(jwt), productId);
  }

  @PostMapping("/merge")
  public List<WishlistItemResponse> merge(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WishlistMergeRequest request) {
    return wishlist.merge(userId(jwt), request.items());
  }

  private static UUID userId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
