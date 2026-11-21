package com.acme.ecommerce.cart.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.cart.api.dto.CartItemRequest;
import com.acme.ecommerce.cart.api.dto.CartResponse;
import com.acme.ecommerce.cart.service.CartService;
import com.acme.ecommerce.cart.support.AbstractCartIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Contract pinned for order-service (TD-BE-15): every cart line it reads carries {@code addedAt}.
 * order-service fingerprints the cart from it and refuses to place an order without one (503), so
 * a null here would turn every cart checkout into an error.
 */
class CartResponseContractTest extends AbstractCartIntegrationTest {

  @Autowired private CartService carts;

  private static CartItemRequest item(UUID productId, int qty) {
    return new CartItemRequest(
        productId, qty, "Item", new BigDecimal("100.00"), "INR", null, null, null, null, null);
  }

  @Test
  @DisplayName("every line of a read cart has a non-null addedAt (added, merged, updated)")
  void everyLineHasAddedAt() {
    UUID user = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    carts.addItem(user, item(first, 1));
    carts.merge(user, List.of(item(first, 2), item(UUID.randomUUID(), 1)));
    carts.addItem(user, item(first, 1));

    CartResponse cart = carts.get(user);

    assertThat(cart.items()).hasSize(2);
    assertThat(cart.items()).allSatisfy(i -> assertThat(i.addedAt()).isNotNull());
  }
}
