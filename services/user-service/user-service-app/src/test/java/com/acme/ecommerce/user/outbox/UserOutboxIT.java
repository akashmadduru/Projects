package com.acme.ecommerce.user.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.user.outbox.UserOutbox.UserDeletedEvent;
import com.acme.ecommerce.user.support.AbstractUserIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class UserOutboxIT extends AbstractUserIntegrationTest {

  @Autowired private UserOutbox outbox;
  @Autowired private JdbcClient jdbc;

  private static UserDeletedEvent event(UUID user) {
    return new UserDeletedEvent(
        UUID.randomUUID().toString(), "USER_DELETED", user.toString(), Instant.now().toString());
  }

  @Test
  @DisplayName("AC-5: enqueue twice for one user gives one row")
  void enqueueIsIdempotent() {
    UUID user = UUID.randomUUID();
    outbox.enqueue(user, event(user));
    outbox.enqueue(user, event(user));
    assertThat(
            jdbc.sql("SELECT count(*) FROM users.outbox WHERE aggregate_id = :u")
                .param("u", user)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("AC-10: payload keys are exactly eventId, type, userId, occurredAt")
  void payloadKeys() throws Exception {
    UUID user = UUID.randomUUID();
    outbox.enqueue(user, event(user));
    String payload =
        jdbc.sql("SELECT CAST(payload AS text) FROM users.outbox WHERE aggregate_id = :u")
            .param("u", user)
            .query(String.class)
            .single();
    JsonNode node = new ObjectMapper().readTree(payload);
    List<String> keys = new ArrayList<>();
    node.fieldNames().forEachRemaining(keys::add);
    assertThat(keys).containsExactlyInAnyOrder("eventId", "type", "userId", "occurredAt");
    assertThat(node.get("userId").asText()).isEqualTo(user.toString());
  }

  @Test
  @DisplayName("C5: serialised event equals the legacy hand-built string")
  void wireFormatMatchesLegacy() {
    UUID user = UUID.randomUUID();
    String eventId = UUID.randomUUID().toString();
    String at = Instant.now().toString();
    String legacy =
        "{\"eventId\":\""
            + eventId
            + "\",\"type\":\"USER_DELETED\",\"userId\":\""
            + user
            + "\",\"occurredAt\":\""
            + at
            + "\"}";
    assertThat(
            UserOutbox.toJson(new UserDeletedEvent(eventId, "USER_DELETED", user.toString(), at)))
        .isEqualTo(legacy);
  }
}
