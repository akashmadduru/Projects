package com.acme.ecommerce.promotion.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.acme.ecommerce.promotion.support.AbstractPromotionIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** AC-7: at-least-once USER_DELETED delivery is harmless for promotion-service. */
class UserDeletedListenerIdempotencyIT extends AbstractPromotionIntegrationTest {

  @Autowired private UserDeletedListener.Listener listener;
  @Autowired private JdbcClient jdbc;

  private void quote(UUID user, String status) {
    jdbc.sql(
            "INSERT INTO promotion.exchange_quotes (user_id, program_id, device_brand,"
                + " device_model, device_condition, age_years, quoted_value, currency, expires_at,"
                + " status) VALUES (:u, (SELECT id FROM promotion.exchange_programs LIMIT 1),"
                + " 'Acme', 'X1', 'WORKING', 2, 50, 'USD', now() + interval '1 day', :s)")
        .param("u", user)
        .param("s", status)
        .update();
  }

  private List<Map<String, Object>> state(UUID user) {
    return jdbc.sql(
            "SELECT status, quoted_value FROM promotion.exchange_quotes WHERE user_id = :u"
                + " ORDER BY status")
        .param("u", user)
        .query()
        .listOfRows();
  }

  @Test
  @DisplayName("delivering USER_DELETED twice gives the state of delivering it once")
  void twiceEqualsOnce() {
    UUID user = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    quote(user, "QUOTED");
    quote(user, "APPLIED");
    quote(other, "QUOTED");
    List<Map<String, Object>> otherBefore = state(other);
    String payload =
        "{\"eventId\":\""
            + UUID.randomUUID()
            + "\",\"type\":\"USER_DELETED\",\"userId\":\""
            + user
            + "\",\"occurredAt\":\"2026-11-21T00:00:00Z\"}";

    listener.onEvent(payload);
    List<Map<String, Object>> afterOnce = state(user);
    assertThat(afterOnce).extracting(r -> r.get("status")).containsExactly("APPLIED", "REJECTED");

    assertThatCode(() -> listener.onEvent(payload)).doesNotThrowAnyException();
    assertThat(state(user)).isEqualTo(afterOnce);
    assertThat(state(other)).isEqualTo(otherBefore);
  }
}
