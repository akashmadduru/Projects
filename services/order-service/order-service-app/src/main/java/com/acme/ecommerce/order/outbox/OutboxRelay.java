package com.acme.ecommerce.order.outbox;

import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.order.event.OrderEvent;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.outbox.Outbox.Kind;
import com.acme.ecommerce.order.service.OrderRemote;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers outbox rows (TD-P6-01). Every 2 s, on one replica ({@link JobLock}), it takes due rows
 * oldest first and runs the matching call. A row waits while an older row of the same aggregate is
 * still pending, so one order's events and effects happen in the order they were recorded. A
 * failure is retried with exponential backoff (2 s doubling, capped at an hour); after {@link
 * #MAX_ATTEMPTS} the row is {@code DEAD}, logged at ERROR as "OUTBOX DEAD" for alerting, and stays
 * listed for an admin to retry. Delivery is at least once; every receiver is idempotent.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  static final int MAX_ATTEMPTS = 20;
  static final int BATCH = 100;
  static final int MAX_ROUNDS = 10;
  private static final long MAX_BACKOFF_SECONDS = 3600;

  record Row(UUID id, UUID aggregateId, Kind kind, String payload, int attempts) {}

  /** Result of one pass, for logs and tests. */
  public record Result(int delivered, int failed, int dead) {}

  private final JdbcClient jdbc;
  private final OrderRemote remote;
  private final OrderEventPublisher events;
  private final JobLock jobLock;

  public OutboxRelay(
      JdbcClient jdbc, OrderRemote remote, OrderEventPublisher events, JobLock jobLock) {
    this.jdbc = jdbc;
    this.remote = remote;
    this.events = events;
    this.jobLock = jobLock;
  }

  @Scheduled(
      fixedDelayString = "${order.outbox.relay-interval-ms:2000}",
      initialDelayString = "${order.outbox.initial-delay-ms:10000}")
  public void scheduledRun() {
    try {
      jobLock.runExclusive("order-outbox-relay", this::relay);
    } catch (RuntimeException e) {
      log.warn("Outbox relay pass failed: {}", e.toString());
    }
  }

  /**
   * One pass. Each round takes the oldest due row of every aggregate; a delivered row unblocks the
   * next one of its aggregate, so rounds repeat while they deliver (bounded, so a busy outbox still
   * yields between passes).
   */
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
      log.info("Outbox relay: delivered={} failed={} dead={}", delivered, failed, dead);
    }
    return new Result(delivered, failed, dead);
  }

  private Result round() {
    List<Row> due =
        jdbc.sql(
                "SELECT o.id, o.aggregate_id, o.kind, CAST(o.payload AS text) AS payload, o.attempts"
                    + " FROM orders.outbox o"
                    + " WHERE o.status = 'PENDING' AND o.next_attempt_at <= now()"
                    + " AND NOT EXISTS (SELECT 1 FROM orders.outbox e WHERE e.status = 'PENDING'"
                    + "   AND e.aggregate_id = o.aggregate_id AND e.seq < o.seq)"
                    + " ORDER BY o.seq LIMIT :n")
            .param("n", BATCH)
            .query(
                (rs, n) ->
                    new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        Kind.valueOf(rs.getString("kind")),
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
                "UPDATE orders.outbox SET status = 'DONE', done_at = now(), attempts = attempts + 1,"
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
    UUID id = row.aggregateId();
    switch (row.kind()) {
      case ORDER_EVENT -> events.sendNow(id, read(row.payload(), OrderEvent.class));
      case RESTORE_STOCK -> remote.restoreStockNow(id);
      case RELEASE_STOCK ->
          remote.releaseStockNow(id, read(row.payload(), OrderRemote.ReleasePayload.class));
      case RELEASE_DEALS -> remote.releaseDealsNow(id);
      case RELEASE_EXCHANGE -> remote.releaseExchangeNow(id);
      case RELEASE_COUPON -> remote.releaseCouponNow(id);
      case RELEASE_COINS -> remote.releaseCoinsNow(id);
      case CANCEL_PAYMENT -> remote.cancelPaymentNow(id);
      case COD_COLLECTED -> remote.codCollectedNow(id);
    }
  }

  /** Records a failed attempt; returns true when the row is now DEAD. */
  private boolean fail(Row row, RuntimeException e) {
    int attempts = row.attempts() + 1;
    String error = e.toString();
    error = error.length() > 500 ? error.substring(0, 500) : error;
    boolean dead = attempts >= MAX_ATTEMPTS;
    long backoff = Math.min(MAX_BACKOFF_SECONDS, 2L << Math.min(attempts, 20));
    jdbc.sql(
            "UPDATE orders.outbox SET attempts = :n, last_error = :e, status = :s,"
                + " next_attempt_at = now() + make_interval(secs => :b) WHERE id = :i")
        .param("n", attempts)
        .param("e", error)
        .param("s", dead ? "DEAD" : "PENDING")
        .param("b", backoff)
        .param("i", row.id())
        .update();
    if (dead) {
      log.error(
          "OUTBOX DEAD: {} for {} gave up after {} attempts: {}",
          row.kind(),
          row.aggregateId(),
          attempts,
          error);
    } else {
      log.warn(
          "Outbox {} for {} failed (attempt {}), retrying in {}s: {}",
          row.kind(),
          row.aggregateId(),
          attempts,
          backoff,
          error);
    }
    return dead;
  }

  private static <T> T read(String json, Class<T> type) {
    try {
      return Outbox.JSON.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Corrupt outbox payload for " + type.getSimpleName(), e);
    }
  }
}
