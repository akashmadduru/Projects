package com.acme.ecommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.payment.support.AbstractPaymentIntegrationTest;
import com.acme.ecommerce.payment.web.AdminGiftCardController;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Gift card money path on a real database (TD-P8-01): redeem credits the wallet exactly once, the
 * per-IP failure limit (TD-P8-21), and the support lookup by last 4 characters.
 */
class GiftCardRedeemIntegrationTest extends AbstractPaymentIntegrationTest {

  @Autowired private GiftCardService giftCards;
  @Autowired private WalletService wallet;
  @Autowired private AdminGiftCardController admin;
  @Autowired private JdbcClient jdbc;

  /** Inserts an ACTIVE card for the raw code and returns it in canonical form. */
  private String card(String raw, String amount, String recipient) {
    String code = GiftCardService.canonical(raw);
    jdbc.sql(
            "INSERT INTO payment.gift_cards (amount, currency, status, purchaser_user_id,"
                + " purchaser_email, recipient_email, code_hash, code_last4, code_issued_at)"
                + " VALUES (:a, 'INR', 'ACTIVE', :p, 'buyer@example.com', :r, :h, :l4, now())")
        .param("a", new BigDecimal(amount))
        .param("p", UUID.randomUUID())
        .param("r", recipient)
        .param("h", GiftCardService.hash(code))
        .param("l4", code.substring(code.length() - 4))
        .update();
    return code;
  }

  @Test
  @DisplayName("redeeming credits the wallet once; a second redeem of the same code is refused")
  void redeemCreditsOnce() {
    UUID user = UUID.randomUUID();
    String code = card("AAAA-BBBB-CCCC-DD01", "500.00", "friend@example.com");

    giftCards.redeem(user, code, "203.0.113.9");

    assertThat(wallet.balance(user).balance()).isEqualByComparingTo("500.00");
    assertThatThrownBy(() -> giftCards.redeem(user, code, "203.0.113.9"))
        .isInstanceOf(CodedException.class)
        .extracting(e -> ((CodedException) e).errorCode())
        .isEqualTo(ErrorCode.GIFT_CARD_INVALID);
    assertThat(wallet.balance(user).balance()).isEqualByComparingTo("500.00");
  }

  @Test
  @DisplayName("many accounts guessing from one IP are stopped at the per-IP limit")
  void perIpLimit() {
    String ip = "198.51.100.77";
    for (int i = 0; i < GiftCardService.MAX_FAILED_REDEEMS_PER_IP; i++) {
      UUID guesser =
          UUID.randomUUID(); // a fresh account each time, so the per-user limit never trips
      assertThatThrownBy(() -> giftCards.redeem(guesser, "ZZZZ-ZZZZ-ZZZZ-ZZZZ", ip))
          .extracting(e -> ((CodedException) e).errorCode())
          .isEqualTo(ErrorCode.GIFT_CARD_INVALID);
    }
    String valid = card("AAAA-BBBB-CCCC-DD02", "100.00", "x@example.com");
    assertThatThrownBy(() -> giftCards.redeem(UUID.randomUUID(), valid, ip))
        .extracting(e -> ((CodedException) e).errorCode())
        .isEqualTo(ErrorCode.RATE_LIMITED);
    // Another network is unaffected.
    UUID other = UUID.randomUUID();
    giftCards.redeem(other, valid, "192.0.2.10");
    assertThat(wallet.balance(other).balance()).isEqualByComparingTo("100.00");
  }

  @Test
  @org.springframework.security.test.context.support.WithMockUser(roles = "SUPPORT")
  @DisplayName("support finds a card by last 4 characters and by recipient email")
  void adminLookup() {
    String code = card("AAAA-BBBB-CCCC-QW42", "250.00", "Lookup.Me@example.com");

    assertThat(admin.search("qw42", null))
        .extracting(AdminGiftCardController.GiftCardRow::last4)
        .contains(code.substring(code.length() - 4));
    assertThat(admin.search(null, "lookup.me@example.com")).hasSize(1);
  }
}
