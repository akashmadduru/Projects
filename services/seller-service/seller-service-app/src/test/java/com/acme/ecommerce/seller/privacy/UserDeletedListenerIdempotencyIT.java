package com.acme.ecommerce.seller.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.acme.ecommerce.seller.support.AbstractSellerIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** AC-7: at-least-once USER_DELETED delivery is harmless for seller-service. */
class UserDeletedListenerIdempotencyIT extends AbstractSellerIntegrationTest {

  @Autowired private UserDeletedListener.Listener listener;
  @Autowired private JdbcClient jdbc;

  private UUID seller(UUID user) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO sellers (id, user_id, legal_name, display_name, slug, email, phone,"
                + " gstin, pan, bank_account_last4, bank_account_hash, ifsc, status) VALUES (:i,"
                + " :u, 'Acme Traders', 'Acme', :s, 'a@b.c', '9999999999', '27ABCDE1234F1Z5',"
                + " 'ABCDE1234F', '6789', 'cipher', 'HDFC0001234', 'APPROVED')")
        .param("i", id)
        .param("u", user)
        .param("s", "idem-" + id)
        .update();
    jdbc.sql(
            "INSERT INTO seller_feedback (seller_id, order_id, shipment_id, user_id, rating,"
                + " comment) VALUES (:i, gen_random_uuid(), gen_random_uuid(), :u, 4, 'great')")
        .param("i", id)
        .param("u", user)
        .update();
    return id;
  }

  private List<Map<String, Object>> state(UUID user) {
    return jdbc.sql(
            "SELECT s.legal_name, s.email, s.phone, s.gstin, s.pan, s.bank_account_last4,"
                + " s.bank_account_hash, s.ifsc, s.status, f.comment FROM sellers s LEFT JOIN"
                + " seller_feedback f ON f.user_id = s.user_id WHERE s.user_id = :u")
        .param("u", user)
        .query()
        .listOfRows();
  }

  @Test
  @DisplayName("delivering USER_DELETED twice gives the state of delivering it once")
  void twiceEqualsOnce() {
    UUID user = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    seller(user);
    seller(other);
    List<Map<String, Object>> otherBefore = state(other);
    String payload =
        "{\"eventId\":\""
            + UUID.randomUUID()
            + "\",\"type\":\"USER_DELETED\",\"userId\":\""
            + user
            + "\",\"occurredAt\":\"2026-11-21T00:00:00Z\"}";

    listener.onEvent(payload);
    List<Map<String, Object>> afterOnce = state(user);
    assertThat(afterOnce).hasSize(1);
    assertThat(afterOnce.get(0).get("status")).isEqualTo("SUSPENDED");
    assertThat(afterOnce.get(0).get("email")).isNull();
    assertThat(afterOnce.get(0).get("comment")).isNull();

    assertThatCode(() -> listener.onEvent(payload)).doesNotThrowAnyException();
    assertThat(state(user)).isEqualTo(afterOnce);
    assertThat(state(other)).isEqualTo(otherBefore);
  }
}
