package com.acme.ecommerce.user.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.user.privacy.PrivacyKafkaConfig.PrivacyHttp;
import com.acme.ecommerce.user.privacy.PrivacyService;
import com.acme.ecommerce.user.support.AbstractUserIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** AC-1, AC-2, AC-5: account deletion is one transaction and never talks to Kafka itself. */
class UserDeletionOutboxIT extends AbstractUserIntegrationTest {

  @MockitoBean private PrivacyHttp privacyHttp;

  @MockitoBean(name = "privacyKafkaTemplate")
  private KafkaTemplate<String, String> kafka;

  @Autowired private PrivacyService privacy;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void stubs() {
    reset(kafka);
    when(privacyHttp.get(anyString(), anyString(), any(Object[].class))).thenReturn("{}");
  }

  private UUID seedDue() {
    UUID u = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO users.user_profiles (user_id, email, display_name, phone) VALUES (:u,"
                + " 'del-' || :u || '@example.com', 'Dee Leted', '+15550001111')")
        .param("u", u)
        .update();
    jdbc.sql(
            "INSERT INTO users.addresses (user_id, full_name, line1, city, postal_code, country)"
                + " VALUES (:u, 'Dee Leted', '1 Main St', 'Town', '12345', 'US')")
        .param("u", u)
        .update();
    jdbc.sql(
            "INSERT INTO users.deletion_requests (user_id, email, status, scheduled_for) VALUES"
                + " (:u, 'del@example.com', 'SCHEDULED', now() - interval '1 minute')")
        .param("u", u)
        .update();
    return u;
  }

  private int count(String sql, UUID u) {
    return jdbc.sql(sql).param("u", u).query(Integer.class).single();
  }

  private String requestStatus(UUID u) {
    return jdbc.sql("SELECT status FROM users.deletion_requests WHERE user_id = :u")
        .param("u", u)
        .query(String.class)
        .single();
  }

  private int outboxRows(UUID u) {
    return count("SELECT count(*) FROM users.outbox WHERE aggregate_id = :u", u);
  }

  @Test
  @DisplayName("AC-1: a due request gives one PENDING row, an anonymised profile and COMPLETED")
  void atomic() {
    UUID u = seedDue();
    assertThat(privacy.runDue(Instant.now())).isGreaterThanOrEqualTo(1);

    assertThat(requestStatus(u)).isEqualTo("COMPLETED");
    assertThat(
            count(
                "SELECT count(*) FROM users.outbox WHERE aggregate_id = :u AND status = 'PENDING'"
                    + " AND kind = 'USER_DELETED'",
                u))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM users.addresses WHERE user_id = :u", u)).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM users.user_profiles WHERE user_id = :u AND email IS NULL"
                    + " AND phone IS NULL AND display_name = 'Deleted user'",
                u))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("AC-1: a failing enqueue rolls back the erasure and leaves the request SCHEDULED")
  void forcedFailureRollsBack() {
    UUID u = seedDue();
    jdbc.sql(
            "CREATE OR REPLACE FUNCTION users.fail_outbox() RETURNS trigger AS $$ BEGIN RAISE"
                + " EXCEPTION 'forced outbox failure'; END $$ LANGUAGE plpgsql")
        .update();
    jdbc.sql(
            "CREATE TRIGGER trg_fail_outbox BEFORE INSERT ON users.outbox FOR EACH ROW EXECUTE"
                + " FUNCTION users.fail_outbox()")
        .update();
    try {
      privacy.runDue(Instant.now());
    } finally {
      jdbc.sql("DROP TRIGGER trg_fail_outbox ON users.outbox").update();
      jdbc.sql("DROP FUNCTION users.fail_outbox()").update();
    }

    assertThat(outboxRows(u)).isZero();
    assertThat(requestStatus(u)).isEqualTo("SCHEDULED");
    assertThat(
            count(
                "SELECT count(*) FROM users.user_profiles WHERE user_id = :u AND email LIKE"
                    + " 'del-%'",
                u))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM users.addresses WHERE user_id = :u", u)).isEqualTo(1);
    // keep other tests' runDue counts independent of this leftover request
    jdbc.sql("UPDATE users.deletion_requests SET status = 'CANCELLED' WHERE user_id = :u")
        .param("u", u)
        .update();
  }

  @Test
  @DisplayName("AC-2: Kafka down does not affect completion; the row stays PENDING")
  void kafkaFailureDoesNotMatter() {
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
    UUID u = seedDue();
    privacy.runDue(Instant.now());

    assertThat(requestStatus(u)).isEqualTo("COMPLETED");
    assertThat(
            count(
                "SELECT count(*) FROM users.outbox WHERE aggregate_id = :u AND status = 'PENDING'"
                    + " AND attempts = 0",
                u))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("AC-2: a throwing Kafka template is never called by runDue")
  void kafkaThrowingIsNotCalled() {
    when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException());
    UUID u = seedDue();
    assertThat(privacy.runDue(Instant.now())).isGreaterThanOrEqualTo(1);
    assertThat(requestStatus(u)).isEqualTo("COMPLETED");
    assertThat(outboxRows(u)).isEqualTo(1);
  }

  @Test
  @DisplayName("AC-5: running twice, or re-running a reset request, still gives one row")
  void idempotent() {
    UUID u = seedDue();
    privacy.runDue(Instant.now());
    privacy.runDue(Instant.now());
    assertThat(outboxRows(u)).isEqualTo(1);

    jdbc.sql("UPDATE users.deletion_requests SET status = 'SCHEDULED' WHERE user_id = :u")
        .param("u", u)
        .update();
    privacy.runDue(Instant.now());
    assertThat(outboxRows(u)).isEqualTo(1);
  }

  @Test
  @DisplayName("a deletion cancelled during the grace period leaves no outbox row")
  void cancelLeavesNothing() {
    UUID u = seedDue();
    jdbc.sql(
            "UPDATE users.deletion_requests SET scheduled_for = now() + interval '3 days'"
                + " WHERE user_id = :u")
        .param("u", u)
        .update();
    privacy.cancelDeletion(u);
    privacy.runDue(Instant.now());
    assertThat(requestStatus(u)).isEqualTo("CANCELLED");
    assertThat(outboxRows(u)).isZero();
  }
}
