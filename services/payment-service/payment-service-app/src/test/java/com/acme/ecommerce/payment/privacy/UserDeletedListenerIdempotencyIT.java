package com.acme.ecommerce.payment.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.acme.ecommerce.payment.support.AbstractPaymentIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** AC-7: at-least-once USER_DELETED delivery is harmless for payment-service. */
class UserDeletedListenerIdempotencyIT extends AbstractPaymentIntegrationTest {

  @Autowired private UserDeletedListener.Listener listener;
  @Autowired private JdbcClient jdbc;

  private void giftCard(UUID purchaser) {
    jdbc.sql(
            "INSERT INTO payment.gift_cards (code_hash, amount, currency, status,"
                + " purchaser_user_id, purchaser_email, recipient_email, message) VALUES"
                + " (encode(sha256(convert_to(gen_random_uuid()::text, 'UTF8')), 'hex'), 25, 'USD',"
                + " 'ACTIVE', :u, 'buyer@example.com', 'friend@example.com', 'happy birthday')")
        .param("u", purchaser)
        .update();
  }

  private List<Map<String, Object>> state(UUID purchaser) {
    return jdbc.sql(
            "SELECT status, purchaser_email, recipient_email, message FROM payment.gift_cards"
                + " WHERE purchaser_user_id = :u ORDER BY recipient_email")
        .param("u", purchaser)
        .query()
        .listOfRows();
  }

  @Test
  @DisplayName("delivering USER_DELETED twice gives the state of delivering it once")
  void twiceEqualsOnce() {
    UUID user = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    giftCard(user);
    giftCard(user);
    giftCard(other);
    List<Map<String, Object>> otherBefore = state(other);
    String payload =
        "{\"eventId\":\""
            + UUID.randomUUID()
            + "\",\"type\":\"USER_DELETED\",\"userId\":\""
            + user
            + "\",\"occurredAt\":\"2026-11-21T00:00:00Z\"}";

    listener.onEvent(payload);
    List<Map<String, Object>> afterOnce = state(user);
    assertThat(afterOnce).hasSize(2);
    assertThat(afterOnce)
        .allSatisfy(
            r -> {
              assertThat(r.get("purchaser_email")).isNull();
              assertThat(r.get("message")).isNull();
              assertThat(r.get("recipient_email")).isEqualTo("deleted@invalid");
            });

    assertThatCode(() -> listener.onEvent(payload)).doesNotThrowAnyException();
    assertThat(state(user)).isEqualTo(afterOnce);
    assertThat(state(other)).isEqualTo(otherBefore);
  }
}
