package com.acme.ecommerce.order.outbox;

import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.order.event.OrderEvent;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.outbox.Outbox.Kind;
import com.acme.ecommerce.order.service.OrderRemote;
import com.fasterxml.jackson.core.JsonProcessingException;
import feign.FeignException;
import java.util.Arrays;
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
 * failure is retried with exponential backoff (2 s doubling, capped at an hour; CLEAR_CART rows cap
 * at 4 minutes and give up after 400 attempts); after {@link #MAX_ATTEMPTS} the row is {@code
 * DEAD}, logged at ERROR as "OUTBOX DEAD" for alerting, and stays listed for an admin to retry.
 * Delivery is at least once; every receiver is idempotent.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  static final int MAX_ATTEMPTS = 20;
  static final int BATCH = 100;
  static final int MAX_ROUNDS = 10;
  private static final long MAX_BACKOFF_SECONDS = 3600;

  /** The cart clear is cheap and wanted soon: retry at most every 4 minutes, for about 26 hours. */
  static final int CLEAR_CART_MAX_ATTEMPTS = 400;

  private static final long CLEAR_CART_MAX_BACKOFF_SECONDS = 240;

  record Row(UUID id, UUID aggregateId, Kind kind, String payload, int attempts) {}

  private static final List<String> KNOWN = Arrays.stream(Kind.values()).map(Kind::name).toList();
  private static final int UNKNOWN_SAMPLE = 5;

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
    reportUnknown();
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

  /**
   * Rows written by a newer build (rollback) are not ours to deliver or discard. They stay PENDING
   * (and still hold back newer rows of their aggregate) but never take a batch slot. Reported once
   * per pass, with a capped sample, so a long rollback window does not flood the log.
   */
  private void reportUnknown() {
    Long count =
        jdbc.sql("SELECT count(*) FROM orders.outbox WHERE status = 'PENDING' AND kind NOT IN (:k)")
            .param("k", KNOWN)
            .query(Long.class)
            .single();
    if (count == null || count == 0) {
      return;
    }
    List<String> sample =
        jdbc.sql(
                "SELECT id || ':' || kind FROM orders.outbox WHERE status = 'PENDING'"
                    + " AND kind NOT IN (:k) ORDER BY seq LIMIT :n")
            .param("k", KNOWN)
            .param("n", UNKNOWN_SAMPLE)
            .query(String.class)
            .list();
    log.error(
        "Outbox has {} PENDING rows of unknown kind (written by a newer build); skipped, left"
            + " PENDING. Sample (id:kind, up to {}): {}",
        count,
        UNKNOWN_SAMPLE,
        sample);
  }

  private Result round() {
    List<Row> due =
        jdbc.sql(
                "SELECT o.id, o.aggregate_id, o.kind, CAST(o.payload AS text) AS payload, o.attempts"
                    + " FROM orders.outbox o"
                    + " WHERE o.status = 'PENDING' AND o.next_attempt_at <= now()"
                    + " AND o.kind IN (:known)"
                    + " AND NOT EXISTS (SELECT 1 FROM orders.outbox e WHERE e.status = 'PENDING'"
                    + "   AND e.aggregate_id = o.aggregate_id AND e.seq < o.seq)"
                    + " ORDER BY o.seq LIMIT :n")
            .param("n", BATCH)
            .param("known", KNOWN)
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
      case CLEAR_CART ->
          remote.clearCartNow(read(row.payload(), OrderRemote.ClearCartPayload.class));
    }
  }

  /** Records a failed attempt; returns true when the row is now DEAD. */
  private boolean fail(Row row, RuntimeException e) {
    int attempts = row.attempts() + 1;
    String error = e.toString();
    error = error.length() > 500 ? error.substring(0, 500) : error;
    boolean clearCart = row.kind() == Kind.CLEAR_CART;
    boolean dead =
        attempts >= (clearCart ? CLEAR_CART_MAX_ATTEMPTS : MAX_ATTEMPTS)
            || (clearCart && permanentClientError(e));
    long backoff =
        Math.min(
            clearCart ? CLEAR_CART_MAX_BACKOFF_SECONDS : MAX_BACKOFF_SECONDS,
            2L << Math.min(attempts, 20));
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

  /** A 4xx other than 404/408/429 will not succeed on retry (validation drift): go DEAD now. */
  private static boolean permanentClientError(RuntimeException e) {
    if (!(e instanceof FeignException fe)) {
      return false;
    }
    int s = fe.status();
    return s >= 400 && s < 500 && s != 404 && s != 408 && s != 429;
  }

  private static <T> T read(String json, Class<T> type) {
    try {
      return Outbox.JSON.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Corrupt outbox payload for " + type.getSimpleName(), e);
    }
  }
}
