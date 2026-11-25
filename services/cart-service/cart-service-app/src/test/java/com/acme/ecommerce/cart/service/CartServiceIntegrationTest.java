package com.acme.ecommerce.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.cart.api.dto.CartItemRequest;
import com.acme.ecommerce.cart.api.dto.CartResponse;
import com.acme.ecommerce.cart.support.AbstractCartIntegrationTest;
import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Cart rules against the real schema (TD-TEST-01): merge, caps, currency, saved items, isolation.
 */
class CartServiceIntegrationTest extends AbstractCartIntegrationTest {

  @Autowired private CartService carts;

  private final UUID user = UUID.randomUUID();

  private static CartItemRequest item(UUID productId, int qty, String currency) {
    return new CartItemRequest(
        productId, qty, "Item", new BigDecimal("100.00"), currency, null, null, null, null, null);
  }

  @Test
  @DisplayName("merging a guest cart sums quantities into existing lines, capped at 99")
  void mergeSumsAndCaps() {
    UUID p = UUID.randomUUID();
    carts.addItem(user, item(p, 60, "INR"));

    CartResponse merged =
        carts.merge(user, List.of(item(p, 60, "INR"), item(UUID.randomUUID(), 2, "INR")));

    assertThat(merged.items()).hasSize(2);
    assertThat(
            merged.items().stream()
                .filter(i -> i.productId().equals(p))
                .findFirst()
                .orElseThrow()
                .quantity())
        .isEqualTo(99);
  }

  @Test
  @DisplayName("a merge that breaks a rule changes nothing (all or nothing)")
  void mergeIsAtomic() {
    carts.addItem(user, item(UUID.randomUUID(), 1, "INR"));

    assertThatThrownBy(
            () ->
                carts.merge(
                    user,
                    List.of(item(UUID.randomUUID(), 1, "INR"), item(UUID.randomUUID(), 1, "USD"))))
        .isInstanceOf(CodedException.class)
        .satisfies(
            e ->
                assertThat(((CodedException) e).errorCode())
                    .isEqualTo(ErrorCode.CURRENCY_MISMATCH));

    assertThat(carts.get(user).items()).hasSize(1);
  }

  @Test
  @DisplayName(
      "a cart holds at most 50 distinct lines; adding to an existing line is still allowed")
  void lineCap() {
    List<CartItemRequest> fifty = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      fifty.add(item(UUID.randomUUID(), 1, "INR"));
    }
    carts.merge(user, fifty);

    assertThatThrownBy(() -> carts.addItem(user, item(UUID.randomUUID(), 1, "INR")))
        .isInstanceOf(CodedException.class)
        .satisfies(
            e -> assertThat(((CodedException) e).errorCode()).isEqualTo(ErrorCode.CART_LIMIT));
    carts.addItem(user, fifty.get(0));
    assertThat(carts.get(user).items()).hasSize(50);
  }

  @Test
  @DisplayName("save for later and back: the line returns with quantity 1")
  void saveForLaterRoundTrip() {
    UUID p = UUID.randomUUID();
    carts.addItem(user, item(p, 3, "INR"));

    CartResponse saved = carts.saveForLater(user, p, null, null);
    assertThat(saved.items()).isEmpty();
    assertThat(saved.savedItems()).hasSize(1);

    CartResponse back = carts.moveSavedToCart(user, p, null, null);
    assertThat(back.savedItems()).isEmpty();
    assertThat(back.items()).singleElement().satisfies(i -> assertThat(i.quantity()).isEqualTo(1));
  }

  @Test
  @DisplayName("changing the quantity of a line the user does not have is a 404")
  void setQuantityUnknownLine() {
    assertThatThrownBy(() -> carts.setQuantity(user, UUID.randomUUID(), null, null, 2))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  @DisplayName("carts are per user: one customer never sees or changes another's lines")
  void cartsAreIsolated() {
    UUID other = UUID.randomUUID();
    UUID p = UUID.randomUUID();
    carts.addItem(other, item(p, 2, "INR"));

    assertThat(carts.get(user).items()).isEmpty();
    assertThatThrownBy(() -> carts.setQuantity(user, p, null, null, 5))
        .isInstanceOf(ResourceNotFoundException.class);
    carts.clear(user);
    assertThat(carts.get(other).items())
        .singleElement()
        .satisfies(i -> assertThat(i.quantity()).isEqualTo(2));
  }
}
