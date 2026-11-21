package com.acme.ecommerce.user.outbox;

import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.user.outbox.UserOutbox.UserDeletedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers USER_DELETED outbox rows to {@code user.events} (TD-P6-33); same semantics as
 * order-service's OutboxRelay. Every 2 s, on one replica ({@link JobLock}), it sends due rows
 * oldest first; a row waits while an older row of the same user is pending. Failures back off
 * exponentially; after {@link #MAX_ATTEMPTS} the row is DEAD and logged at ERROR as "OUTBOX DEAD".
 * At-least-once; every USER_DELETED consumer is idempotent.
 *
 * <p>PII rule: {@code last_error} holds the exception class and the root-cause message only, cut to
 * 500 characters. The payload is never part of an error text.
 */
@Component
public class UserOutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(UserOutboxRelay.class);

  static final String TOPIC = "user.events";
  static final int MAX_ATTEMPTS = 20;
  static final int BATCH = 100;
  static final int MAX_ROUNDS = 10;
  private static final long MAX_BACKOFF_SECONDS = 3600;

  record Row(UUID id, UUID aggregateId, String payload, int attempts) {}

  /** Result of one pass, for logs and tests. */
  public record Result(int delivered, int failed, int dead) {}

  private final JdbcClient jdbc;
  private final KafkaTemplate<String, String> kafka;
  private final JobLock jobLock;

  public UserOutboxRelay(
      JdbcClient jdbc,
      @Qualifier("privacyKafkaTemplate") KafkaTemplate<String, String> kafka,
      JobLock jobLock) {
    this.jdbc = jdbc;
    this.kafka = kafka;
    this.jobLock = jobLock;
  }

  @Scheduled(
      fixedDelayString = "${user.outbox.relay-interval-ms:2000}",
      initialDelayString = "${user.outbox.initial-delay-ms:10000}")
  public void scheduledRun() {
    try {
      jobLock.runExclusive("user-outbox-relay", this::relay);
    } catch (RuntimeException e) {
      log.warn("User outbox relay pass failed: {}", e.toString());
    }
  }

  /** One pass: rounds repeat while they deliver (bounded, so a busy outbox still yields). */
  public Result relay() {
    int delivered = 0;
    int failed = 0;
    int dead = 0;
    for (int round = 0; round < MAX_ROUNDS; round++) {
      Result r = round();
      delivered += r.delivered();
      failed += r.failed();
      dead += r.dead();
      if (r.delivered() + r.dead() == 0) {
        break;
      }
    }
    if (delivered + failed + dead > 0) {
      log.info("User outbox relay: delivered={} failed={} dead={}", delivered, failed, dead);
    }
    return new Result(delivered, failed, dead);
  }

  private Result round() {
    List<Row> due =
        jdbc.sql(
                "SELECT o.id, o.aggregate_id, CAST(o.payload AS text) AS payload, o.attempts"
                    + " FROM users.outbox o"
                    + " WHERE o.status = 'PENDING' AND o.next_attempt_at <= now()"
                    + " AND NOT EXISTS (SELECT 1 FROM users.outbox e WHERE e.status = 'PENDING'"
                    + "   AND e.aggregate_id = o.aggregate_id AND e.seq < o.seq)"
                    + " ORDER BY o.seq LIMIT :n")
            .param("n", BATCH)
            .query(
                (rs, n) ->
                    new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("payload"),
                        rs.getInt("attempts")))
            .list();
    int delivered = 0;
    int failed = 0;
    int dead = 0;
    for (Row row : due) {
      try {
        deliver(row);
        jdbc.sql(
                "UPDATE users.outbox SET status = 'DONE', done_at = now(), attempts = attempts + 1,"
                    + " last_error = NULL WHERE id = :i")
            .param("i", row.id())
            .update();
        delivered++;
      } catch (RuntimeException e) {
        if (fail(row, e)) {
          dead++;
        } else {
          failed++;
        }
      }
    }
    return new Result(delivered, failed, dead);
  }

  private void deliver(Row row) {
    String json;
    try {
      json = UserOutbox.JSON.writeValueAsString(read(row.payload()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Corrupt outbox payload");
    }
    try {
      kafka.send(TOPIC, row.aggregateId().toString(), json).get(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted publishing USER_DELETED", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("could not publish USER_DELETED", e);
    }
  }

  /** Records a failed attempt; returns true when the row is now DEAD. */
  private boolean fail(Row row, RuntimeException e) {
    int attempts = row.attempts() + 1;
    String error = describe(e);
    boolean dead = attempts >= MAX_ATTEMPTS;
    long backoff = Math.min(MAX_BACKOFF_SECONDS, 2L << Math.min(attempts, 20));
    jdbc.sql(
            "UPDATE users.outbox SET attempts = :n, last_error = :e, status = :s,"
                + " next_attempt_at = now() + make_interval(secs => :b) WHERE id = :i")
        .param("n", attempts)
        .param("e", error)
        .param("s", dead ? "DEAD" : "PENDING")
        .param("b", backoff)
        .param("i", row.id())
        .update();
    if (dead) {
      log.error(
          "OUTBOX DEAD: USER_DELETED for {} gave up after {} attempts: {}",
          row.aggregateId(),
          attempts,
          error);
    } else {
      log.warn(
          "User outbox USER_DELETED for {} failed (attempt {}), retrying in {}s: {}",
          row.aggregateId(),
          attempts,
          backoff,
          error);
    }
    return dead;
  }

  /** Exception class plus root-cause message, truncated; never the payload. */
  static String describe(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String text = e.getClass().getName();
    if (root != e || e.getMessage() != null) {
      text += ": " + root.getMessage();
    }
    return text.length() > 500 ? text.substring(0, 500) : text;
  }

  private static UserDeletedEvent read(String json) throws JsonProcessingException {
    return UserOutbox.JSON.readValue(json, UserDeletedEvent.class);
  }
}
