package com.acme.ecommerce.cart.service;

import com.acme.ecommerce.cart.api.dto.CartItemRequest;
import com.acme.ecommerce.cart.api.dto.CartItemResponse;
import com.acme.ecommerce.cart.api.dto.CartResponse;
import com.acme.ecommerce.cart.repository.CartRepository;
import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cart use cases. A cart holds lines in ONE currency; the owner is always the JWT subject. */
@Service
public class CartService {

  /** Matches the order-service checkout limit. */
  static final int MAX_LINES = 50;

  /** Cap on "saved for later" lines per user. */
  static final int MAX_SAVED = 50;

  private final CartRepository carts;

  public CartService(CartRepository carts) {
    this.carts = carts;
  }

  @Transactional(readOnly = true)
  public CartResponse get(UUID userId) {
    return view(userId);
  }

  @Transactional
  public CartResponse addItem(UUID userId, CartItemRequest item) {
    UUID cartId = carts.lockOrCreateCart(userId);
    add(cartId, item);
    return view(userId);
  }

  /** Sums each incoming line into the server cart (quantity capped at 99). All-or-nothing. */
  @Transactional
  public CartResponse merge(UUID userId, List<CartItemRequest> items) {
    UUID cartId = carts.lockOrCreateCart(userId);
    for (CartItemRequest item : items) {
      add(cartId, item);
    }
    return view(userId);
  }

  @Transactional
  public CartResponse setQuantity(UUID userId, UUID productId, int quantity) {
    UUID cartId = carts.lockOrCreateCart(userId);
    if (carts.setQuantity(cartId, productId, quantity) == 0) {
      throw new ResourceNotFoundException("CartItem", productId);
    }
    return view(userId);
  }

  @Transactional
  public void removeItem(UUID userId, UUID productId) {
    carts.deleteItem(carts.lockOrCreateCart(userId), productId);
  }

  @Transactional
  public void clear(UUID userId) {
    carts.clear(carts.lockOrCreateCart(userId));
  }

  /** Moves a cart line to "saved for later" (cap 50 saved lines). */
  @Transactional
  public CartResponse saveForLater(UUID userId, UUID productId) {
    UUID cartId = carts.lockOrCreateCart(userId);
    if (!carts.hasSaved(userId, productId) && carts.countSaved(userId) >= MAX_SAVED) {
      throw new CodedException(
          ErrorCode.CART_LIMIT, "Saved for later is limited to " + MAX_SAVED + " items");
    }
    if (carts.saveLineForLater(cartId, userId, productId) == 0) {
      throw new ResourceNotFoundException("CartItem", productId);
    }
    carts.deleteItem(cartId, productId);
    return view(userId);
  }

  /** Moves a saved line back into the cart with quantity 1 (currency rule applies). */
  @Transactional
  public CartResponse moveSavedToCart(UUID userId, UUID productId) {
    UUID cartId = carts.lockOrCreateCart(userId);
    CartItemRequest item =
        carts
            .findSavedAsRequest(userId, productId)
            .orElseThrow(() -> new ResourceNotFoundException("SavedItem", productId));
    add(cartId, item);
    carts.deleteSaved(userId, productId);
    return view(userId);
  }

  @Transactional
  public CartResponse removeSaved(UUID userId, UUID productId) {
    carts.deleteSaved(userId, productId);
    return view(userId);
  }

  private void add(UUID cartId, CartItemRequest item) {
    String currency = item.currency().toUpperCase(Locale.ROOT);
    boolean mismatch = carts.currencies(cartId).stream().anyMatch(c -> !c.equals(currency));
    if (mismatch) {
      throw new CodedException(
          ErrorCode.CURRENCY_MISMATCH, "Cart already contains items in a different currency");
    }
    if (!carts.hasLine(cartId, item.productId()) && carts.countLines(cartId) >= MAX_LINES) {
      throw new CodedException(
          ErrorCode.CART_LIMIT, "Cart is limited to " + MAX_LINES + " distinct items");
    }
    carts.addOrIncrement(cartId, item);
  }

  private CartResponse view(UUID userId) {
    List<CartItemResponse> items = carts.findItems(userId);
    int count = items.stream().mapToInt(CartItemResponse::quantity).sum();
    BigDecimal subtotal =
        items.stream().map(CartItemResponse::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    String currency = items.isEmpty() ? null : items.get(0).currency();
    return new CartResponse(items, currency, count, subtotal, carts.findSaved(userId));
  }
}
