package com.acme.ecommerce.cart.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.cart.api.dto.CartItemRequest;
import com.acme.ecommerce.cart.api.dto.CartItemResponse;
import com.acme.ecommerce.cart.api.dto.ConsumedLinesRequest;
import com.acme.ecommerce.cart.support.AbstractCartIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * F-20261119-11 / TD-BE-03: the consumed-lines removal removes exactly what an order consumed, from
 * that user's cart only (AC-16, AC-18, AC-19).
 */
class CartConsumedLinesIntegrationTest extends AbstractCartIntegrationTest {

  @Autowired private CartService carts;
  @Autowired private JdbcTemplate jdbc;

  private final UUID user = UUID.randomUUID();
  private final UUID other = UUID.randomUUID();

  private static CartItemRequest item(UUID productId, int qty, UUID variantId, UUID offerId) {
    return new CartItemRequest(
        productId,
        qty,
        "Item",
        new BigDecimal("100.00"),
        "INR",
        null,
        variantId,
        null,
        offerId,
        null);
  }

  private static ConsumedLinesRequest consumed(List<CartItemResponse> items) {
    return new ConsumedLinesRequest(
        UUID.randomUUID(),
        items.stream()
            .map(
                i ->
                    new ConsumedLinesRequest.Line(
                        i.productId(), i.variantId(), i.offerId(), i.quantity(), i.addedAt()))
            .toList());
  }

  @Test
  @DisplayName("a matching line is removed and the count is returned")
  void matchingLineIsRemoved() {
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    UUID variant = UUID.randomUUID();
    UUID offer = UUID.randomUUID();
    carts.addItem(user, item(p1, 2, null, null));
    carts.addItem(user, item(p2, 1, variant, offer));
    List<CartItemResponse> snapshot = carts.get(user).items();

    int removed = carts.removeConsumed(user, consumed(snapshot));

    assertThat(removed).isEqualTo(2);
    assertThat(carts.get(user).items()).isEmpty();
  }

  @Test
  @DisplayName("a user with no cart gets removed=0 and no cart row is created")
  void noCartCreatesNothing() {
    UUID stranger = UUID.randomUUID();
    ConsumedLinesRequest request =
        new ConsumedLinesRequest(
            UUID.randomUUID(),
            List.of(
                new ConsumedLinesRequest.Line(
                    UUID.randomUUID(), null, null, 1, Instant.parse("2026-11-20T10:00:00Z"))));

    assertThat(carts.removeConsumed(stranger, request)).isZero();

    assertThat(
            jdbc.queryForObject(
                "select count(*) from cart.carts where user_id = ?", Integer.class, stranger))
        .isZero();
  }

  @Test
  @DisplayName("AC-16: a repeated call is a no-op and returns 0")
  void repeatIsIdempotent() {
    carts.addItem(user, item(UUID.randomUUID(), 1, null, null));
    ConsumedLinesRequest request = consumed(carts.get(user).items());

    assertThat(carts.removeConsumed(user, request)).isEqualTo(1);
    assertThat(carts.removeConsumed(user, request)).isZero();
    assertThat(carts.get(user).items()).isEmpty();
  }

  @Test
  @DisplayName("AC-18: a line whose quantity changed after the order survives")
  void changedQuantitySurvives() {
    UUID p = UUID.randomUUID();
    UUID q = UUID.randomUUID();
    carts.addItem(user, item(p, 1, null, null));
    carts.addItem(user, item(q, 1, null, null));
    ConsumedLinesRequest request = consumed(carts.get(user).items());
    carts.addItem(user, item(p, 1, null, null)); // p is now quantity 2

    int removed = carts.removeConsumed(user, request);

    assertThat(removed).isEqualTo(1);
    List<CartItemResponse> left = carts.get(user).items();
    assertThat(left).hasSize(1);
    assertThat(left.get(0).productId()).isEqualTo(p);
    assertThat(left.get(0).quantity()).isEqualTo(2);
  }

  @Test
  @DisplayName("AC-18: a line added after the order survives")
  void laterLineSurvives() {
    UUID consumedProduct = UUID.randomUUID();
    UUID later = UUID.randomUUID();
    carts.addItem(user, item(consumedProduct, 1, null, null));
    ConsumedLinesRequest request = consumed(carts.get(user).items());
    carts.addItem(user, item(later, 1, null, null));

    int removed = carts.removeConsumed(user, request);

    assertThat(removed).isEqualTo(1);
    assertThat(carts.get(user).items())
        .extracting(CartItemResponse::productId)
        .containsExactly(later);
  }

  @Test
  @DisplayName("AC-18: a different offer or variant of the same product is a different line")
  void offerAndVariantMustMatchExactly() {
    UUID p = UUID.randomUUID();
    UUID offer = UUID.randomUUID();
    UUID variant = UUID.randomUUID();
    carts.addItem(user, item(p, 1, variant, offer));
    CartItemResponse line = carts.get(user).items().get(0);
    ConsumedLinesRequest.Line noOffer =
        new ConsumedLinesRequest.Line(p, variant, null, 1, line.addedAt());
    ConsumedLinesRequest.Line otherVariant =
        new ConsumedLinesRequest.Line(p, UUID.randomUUID(), offer, 1, line.addedAt());

    assertThat(
            carts.removeConsumed(
                user, new ConsumedLinesRequest(UUID.randomUUID(), List.of(noOffer, otherVariant))))
        .isZero();
    assertThat(carts.get(user).items()).hasSize(1);
    // Positive sanity: the exact line does match.
    ConsumedLinesRequest.Line exact =
        new ConsumedLinesRequest.Line(p, variant, offer, 1, line.addedAt());
    assertThat(
            carts.removeConsumed(user, new ConsumedLinesRequest(UUID.randomUUID(), List.of(exact))))
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "the add time is compared to the millisecond, so a millisecond-rounded value matches")
  void addedAtMatchesAtMillisecondPrecision() {
    carts.addItem(user, item(UUID.randomUUID(), 1, null, null));
    CartItemResponse line = carts.get(user).items().get(0);
    Instant millis = line.addedAt().truncatedTo(ChronoUnit.MILLIS);

    ConsumedLinesRequest.Line rounded =
        new ConsumedLinesRequest.Line(
            line.productId(), null, null, 1, millis.plusSeconds(5)); // a different time

    assertThat(
            carts.removeConsumed(
                user, new ConsumedLinesRequest(UUID.randomUUID(), List.of(rounded))))
        .isZero();
    ConsumedLinesRequest.Line exactMillis =
        new ConsumedLinesRequest.Line(line.productId(), null, null, 1, millis);
    assertThat(
            carts.removeConsumed(
                user, new ConsumedLinesRequest(UUID.randomUUID(), List.of(exactMillis))))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("AC-19: another user's identical lines are untouched")
  void otherUsersCartIsUntouched() {
    UUID p = UUID.randomUUID();
    UUID variant = UUID.randomUUID();
    carts.addItem(user, item(p, 3, variant, null));
    carts.addItem(other, item(p, 3, variant, null));
    CartItemResponse mine = carts.get(user).items().get(0);
    // Make B's line identical in every matched column, including the add time.
    jdbc.update(
        "UPDATE cart.cart_items SET added_at = ? WHERE cart_id = (SELECT id FROM cart.carts"
            + " WHERE user_id = ?)",
        java.sql.Timestamp.from(mine.addedAt()),
        other);
    CartItemResponse theirs = carts.get(other).items().get(0);
    assertThat(theirs.addedAt()).isEqualTo(mine.addedAt()); // the fixture really is identical

    int removed = carts.removeConsumed(user, consumed(List.of(mine)));

    assertThat(removed).isEqualTo(1);
    assertThat(carts.get(user).items()).isEmpty();
    assertThat(carts.get(other).items()).hasSize(1);
    assertThat(carts.get(other).items().get(0).quantity()).isEqualTo(3);
  }
}
