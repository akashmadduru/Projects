package com.acme.ecommerce.user.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.user.outbox.UserOutbox.UserDeletedEvent;
import com.acme.ecommerce.user.support.AbstractUserIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** AC-3, AC-4, AC-5 (relay once), AC-6, AC-10 for {@link UserOutboxRelay}. */
@ExtendWith(OutputCaptureExtension.class)
class UserOutboxRelayIT extends AbstractUserIntegrationTest {

  @MockitoBean(name = "privacyKafkaTemplate")
  private KafkaTemplate<String, String> kafka;

  @Autowired private UserOutbox outbox;
  @Autowired private UserOutboxRelay relay;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void clean() {
    reset(kafka);
    jdbc.sql("DELETE FROM users.outbox").update();
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
  }

  private static UserDeletedEvent event(UUID user) {
    return new UserDeletedEvent(
        UUID.randomUUID().toString(), "USER_DELETED", user.toString(), Instant.now().toString());
  }

  private UUID enqueued() {
    UUID u = UUID.randomUUID();
    outbox.enqueue(u, event(u));
    return u;
  }

  private String status(UUID u) {
    return jdbc.sql("SELECT status FROM users.outbox WHERE aggregate_id = :u")
        .param("u", u)
        .query(String.class)
        .single();
  }

  private void insertRaw(UUID u, String idem, String payload, String nextAttempt) {
    jdbc.sql(
            "INSERT INTO users.outbox (aggregate_id, kind, payload, idem_key, next_attempt_at)"
                + " VALUES (:u, 'USER_DELETED', CAST(:p AS jsonb), :i, "
                + nextAttempt
                + ")")
        .param("u", u)
        .param("p", payload)
        .param("i", idem)
        .update();
  }

  @Test
  @DisplayName("AC-3: sends once to user.events keyed by userId, legacy wire format, row DONE")
  void deliversOnce() throws Exception {
    UUID u = enqueued();
    relay.relay();

    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(kafka).send(eq("user.events"), eq(u.toString()), json.capture());
    JsonNode node = new ObjectMapper().readTree(json.getValue());
    List<String> keys = new ArrayList<>();
    node.fieldNames().forEachRemaining(keys::add);
    assertThat(keys).containsExactly("eventId", "type", "userId", "occurredAt");
    assertThat(node.get("type").asText()).isEqualTo("USER_DELETED");
    assertThat(node.get("userId").asText()).isEqualTo(u.toString());
    assertThat(Instant.parse(node.get("occurredAt").asText())).isNotNull();
    assertThat(json.getValue())
        .isEqualTo(
            "{\"eventId\":\""
                + node.get("eventId").asText()
                + "\",\"type\":\"USER_DELETED\",\"userId\":\""
                + u
                + "\",\"occurredAt\":\""
                + node.get("occurredAt").asText()
                + "\"}");
    assertThat(status(u)).isEqualTo("DONE");
  }

  @Test
  @DisplayName("AC-5: relay twice sends once")
  void relayTwiceSendsOnce() {
    enqueued();
    relay.relay();
    relay.relay();
    verify(kafka, times(1)).send(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("AC-4: a failed send backs off")
  void failureBacksOff() {
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
    UUID u = enqueued();
    relay.relay();

    assertThat(status(u)).isEqualTo("PENDING");
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM users.outbox WHERE aggregate_id = :u AND attempts = 1"
                        + " AND next_attempt_at > now() + interval '1 second' AND last_error LIKE"
                        + " '%broker down%'")
                .param("u", u)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("AC-4: the 20th failure makes the row DEAD and logs OUTBOX DEAD at ERROR")
  void deadAfterMaxAttempts(CapturedOutput output) {
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
    UUID u = enqueued();
    jdbc.sql("UPDATE users.outbox SET attempts = 19 WHERE aggregate_id = :u")
        .param("u", u)
        .update();
    relay.relay();

    assertThat(status(u)).isEqualTo("DEAD");
    assertThat(output.getAll()).containsPattern("ERROR.*OUTBOX DEAD");
  }

  @Test
  @DisplayName("AC-6: two scheduled runs at once send once")
  void onlyOneRelayRuns() throws Exception {
    CountDownLatch inSend = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              inSend.countDown();
              release.await(20, TimeUnit.SECONDS);
              return CompletableFuture.completedFuture(null);
            });
    enqueued();

    Thread a = new Thread(relay::scheduledRun, "relay-a");
    a.start();
    assertThat(inSend.await(20, TimeUnit.SECONDS)).isTrue();
    relay.scheduledRun(); // B: lock is held, returns without sending
    release.countDown();
    a.join(20_000);

    verify(kafka, times(1)).send(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("AC-6: a newer row waits while an older row of the same user is backed off")
  void ordering() {
    UUID u = UUID.randomUUID();
    String payload = UserOutbox.toJson(event(u));
    insertRaw(u, "ORDER-OLD:" + u, payload, "now() + interval '1 hour'");
    insertRaw(u, "ORDER-NEW:" + u, payload, "now()");
    relay.relay();
    verify(kafka, never()).send(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("a corrupt payload goes DEAD with constant text and blocks nobody else")
  void poisonRowDoesNotBlock() {
    UUID bad = UUID.randomUUID();
    insertRaw(bad, "USER_DELETED:" + bad, "{\"eventId\":{\"v\":\"x\"}}", "now()");
    UUID good = enqueued();
    jdbc.sql("UPDATE users.outbox SET attempts = 19 WHERE aggregate_id = :u")
        .param("u", bad)
        .update();
    relay.relay();

    assertThat(status(bad)).isEqualTo("DEAD");
    assertThat(
            jdbc.sql("SELECT last_error FROM users.outbox WHERE aggregate_id = :u")
                .param("u", bad)
                .query(String.class)
                .single())
        .isEqualTo("java.lang.IllegalStateException: Corrupt outbox payload");
    assertThat(status(good)).isEqualTo("DONE");
  }

  @Test
  @DisplayName("AC-10: neither payload nor last_error carries the user's email, phone or name")
  void noPii() {
    UUID u = UUID.randomUUID();
    String email = "pii-" + u + "@example.com";
    jdbc.sql(
            "INSERT INTO users.user_profiles (user_id, email, display_name, phone) VALUES (:u,"
                + " :e, 'Zed Zebra', '+15559998888')")
        .param("u", u)
        .param("e", email)
        .update();
    outbox.enqueue(u, event(u));
    // a payload the mapper rejects with an input-echoing message (Jackson quotes the source)
    UUID bad = UUID.randomUUID();
    // Jackson echoes an unknown property NAME in its message; here the name is the email
    insertRaw(bad, "USER_DELETED:" + bad, "{\"" + email + "\":\"v\"}", "now()");
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("x".repeat(2000))));
    relay.relay();

    for (UUID id : List.of(u, bad)) {
      var row =
          jdbc.sql(
                  "SELECT CAST(payload AS text) AS payload, last_error FROM users.outbox WHERE"
                      + " aggregate_id = :u")
              .param("u", id)
              .query()
              .singleRow();
      String text = row.get("payload") + "|" + row.get("last_error");
      if (id.equals(u)) {
        assertThat(text).doesNotContain(email).doesNotContain("+15559998888").doesNotContain("Zed");
        assertThat(((String) row.get("last_error")).length()).isLessThanOrEqualTo(500);
      } else {
        assertThat((String) row.get("last_error")).doesNotContain("example.com");
        assertThat((String) row.get("last_error")).doesNotContain(email);
      }
    }
  }
}
