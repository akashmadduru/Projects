package com.acme.ecommerce.user.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes USER_DELETED outbox rows (TD-P6-33). JdbcClient joins the caller's Spring-managed
 * transaction, so the erasure and its event commit or roll back together. Delivery is {@link
 * UserOutboxRelay}'s job. A second request for the same user is ignored (idem key).
 */
@Component
public class UserOutbox {

  /** Jackson 2, no java.time module: the event has String fields only (legacy wire format). */
  static final ObjectMapper JSON = new ObjectMapper();

  static final String KIND = "USER_DELETED";

  /** Wire format of {@code user.events}; field order is part of the contract. */
  public record UserDeletedEvent(String eventId, String type, String userId, String occurredAt) {}

  private final JdbcClient jdbc;

  public UserOutbox(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void enqueue(UUID userId, UserDeletedEvent event) {
    jdbc.sql(
            "INSERT INTO users.outbox (aggregate_id, kind, payload, idem_key)"
                + " VALUES (:a, :k, CAST(:p AS jsonb), :i) ON CONFLICT (idem_key) DO NOTHING")
        .param("a", userId)
        .param("k", KIND)
        .param("p", toJson(event))
        .param("i", KIND + ":" + userId)
        .update();
  }

  static String toJson(UserDeletedEvent event) {
    try {
      return JSON.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Outbox payload is not serialisable");
    }
  }
}
