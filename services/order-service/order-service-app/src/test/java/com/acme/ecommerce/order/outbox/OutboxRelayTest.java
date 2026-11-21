package com.acme.ecommerce.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.acme.ecommerce.cart.api.dto.ConsumedLinesRequest;
import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.outbox.Outbox.Kind;
import com.acme.ecommerce.order.service.OrderRemote;
import com.acme.ecommerce.order.support.AbstractOrderIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Relay tolerance of rows it does not understand (rollback safety for newer outbox kinds). */
class OutboxRelayTest extends AbstractOrderIntegrationTest {

  @MockitoBean private OrderRemote remote;
  @MockitoBean private OrderEventPublisher events;

  @Autowired private Outbox outbox;
  @Autowired private OutboxRelay relay;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private JobLock jobLock;

  /** The database is shared with other test classes: leave no unknown or CLEAR_CART rows behind. */
  @AfterEach
  void removeUnknownRows() {
    jdbc.update("delete from orders.outbox where kind in ('BOGUS', 'CLEAR_CART')");
  }

  @Test
  @DisplayName("an unknown kind is skipped and left PENDING; a valid row behind it is delivered")
  void unknownKindDoesNotStallThePass() {
    UUID bogusAggregate = UUID.randomUUID();
    UUID validAggregate = UUID.randomUUID();
    outbox.enqueue(Kind.RESTORE_STOCK, bogusAggregate);
    jdbc.update("update orders.outbox set kind = 'BOGUS' where aggregate_id = ?", bogusAggregate);
    outbox.enqueue(Kind.RESTORE_STOCK, validAggregate);

    // Other test classes share this database, so rows of theirs may be delivered in the same pass.
    relay.relay();

    verify(remote).restoreStockNow(validAggregate);
    assertThat(
            jdbc.queryForObject(
                "select status from orders.outbox where aggregate_id = ?",
                String.class,
                bogusAggregate))
        .isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "select status from orders.outbox where aggregate_id = ?",
                String.class,
                validAggregate))
        .isEqualTo("DONE");
  }

  @Test
  @DisplayName("more than BATCH unknown rows on distinct aggregates do not starve a valid row")
  void unknownRowsDoNotStarveTheBatch() {
    for (int i = 0; i < OutboxRelay.BATCH + 1; i++) {
      UUID bogus = UUID.randomUUID();
      outbox.enqueue(Kind.RESTORE_STOCK, bogus);
      jdbc.update("update orders.outbox set kind = 'BOGUS' where aggregate_id = ?", bogus);
    }
    UUID valid = UUID.randomUUID();
    outbox.enqueue(Kind.RESTORE_STOCK, valid);

    relay.relay();

    verify(remote).restoreStockNow(valid);
    assertThat(
            jdbc.queryForObject(
                "select status from orders.outbox where aggregate_id = ?", String.class, valid))
        .isEqualTo("DONE");
  }

  // --- CLEAR_CART (F-20261119-11, TD-BE-03) -----------------------------------------------------

  private static final Instant ADDED = Instant.parse("2026-11-20T10:00:00.123Z");

  private UUID clearCartRow(UUID orderId, int attempts) {
    UUID user = UUID.randomUUID();
    UUID aggregate = UUID.randomUUID();
    outbox.enqueue(
        Kind.CLEAR_CART,
        aggregate,
        new OrderRemote.ClearCartPayload(
            user,
            orderId,
            List.of(new ConsumedLinesRequest.Line(UUID.randomUUID(), null, null, 2, ADDED))),
        "CLEAR_CART:" + orderId);
    jdbc.update(
        "update orders.outbox set attempts = ? where aggregate_id = ?", attempts, aggregate);
    return aggregate;
  }

  private String status(UUID aggregate) {
    return jdbc.queryForObject(
        "select status from orders.outbox where aggregate_id = ?", String.class, aggregate);
  }

  private long secondsUntilNextAttempt(UUID aggregate) {
    return jdbc.queryForObject(
        "select extract(epoch from (next_attempt_at - now()))::bigint from orders.outbox"
            + " where aggregate_id = ?",
        Long.class,
        aggregate);
  }

  @Test
  @DisplayName("AC-15/16: a failed clear stays PENDING, retries within 240 s, then goes DONE")
  void clearCartFailsThenSucceeds() {
    UUID order = UUID.randomUUID();
    UUID aggregate = clearCartRow(order, 0);
    doThrow(new IllegalStateException("cart-service down"))
        .when(remote)
        .clearCartNow(any(OrderRemote.ClearCartPayload.class));

    relay.relay();

    assertThat(status(aggregate)).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "select attempts from orders.outbox where aggregate_id = ?",
                Integer.class,
                aggregate))
        .isEqualTo(1);
    assertThat(secondsUntilNextAttempt(aggregate)).isBetween(1L, 240L);

    // The cart-service recovers and the row is due again.
    org.mockito.Mockito.reset(remote);
    jdbc.update(
        "update orders.outbox set next_attempt_at = now() where aggregate_id = ?", aggregate);
    relay.relay();

    assertThat(status(aggregate)).isEqualTo("DONE");
    verify(remote)
        .clearCartNow(
            org.mockito.ArgumentMatchers.argThat(
                p ->
                    p.orderId().equals(order)
                        && p.lines().size() == 1
                        && p.lines().get(0).quantity() == 2
                        && p.lines().get(0).addedAt().equals(ADDED)));
  }

  @Test
  @DisplayName("the CLEAR_CART backoff is capped at 240 s while other kinds keep their 3600 s cap")
  void clearCartBackoffIsCappedPerKind() {
    UUID clear = clearCartRow(UUID.randomUUID(), 14);
    UUID restore = UUID.randomUUID();
    outbox.enqueue(Kind.RESTORE_STOCK, restore);
    jdbc.update("update orders.outbox set attempts = 14 where aggregate_id = ?", restore);
    doThrow(new IllegalStateException("down")).when(remote).clearCartNow(any());
    doThrow(new IllegalStateException("down")).when(remote).restoreStockNow(restore);

    relay.relay();

    assertThat(secondsUntilNextAttempt(clear)).isBetween(200L, 240L);
    assertThat(secondsUntilNextAttempt(restore)).isBetween(3500L, 3600L);
  }

  @Test
  @DisplayName("a CLEAR_CART row survives a restart: a new relay instance delivers it")
  void clearCartSurvivesRestart() {
    UUID order = UUID.randomUUID();
    UUID aggregate = clearCartRow(order, 0);
    doThrow(new IllegalStateException("down")).when(remote).clearCartNow(any());
    relay.relay();
    assertThat(status(aggregate)).isEqualTo("PENDING");

    org.mockito.Mockito.reset(remote);
    jdbc.update(
        "update orders.outbox set next_attempt_at = now() where aggregate_id = ?", aggregate);
    OutboxRelay restarted = new OutboxRelay(jdbcClient, remote, events, jobLock);
    restarted.relay();

    assertThat(status(aggregate)).isEqualTo("DONE");
    verify(remote)
        .clearCartNow(org.mockito.ArgumentMatchers.argThat(p -> p.orderId().equals(order)));
  }

  @Test
  @DisplayName("CLEAR_CART goes DEAD only at 400 attempts; other kinds still die at 20")
  void attemptLimitsPerKind() {
    UUID clearAt399 = clearCartRow(UUID.randomUUID(), 399);
    UUID clearAt19 = clearCartRow(UUID.randomUUID(), 19);
    UUID restoreAt19 = UUID.randomUUID();
    outbox.enqueue(Kind.RESTORE_STOCK, restoreAt19);
    jdbc.update("update orders.outbox set attempts = 19 where aggregate_id = ?", restoreAt19);
    doThrow(new IllegalStateException("down")).when(remote).clearCartNow(any());
    doThrow(new IllegalStateException("down")).when(remote).restoreStockNow(restoreAt19);

    relay.relay();

    assertThat(status(clearAt399)).isEqualTo("DEAD");
    assertThat(status(clearAt19)).isEqualTo("PENDING");
    assertThat(status(restoreAt19)).isEqualTo("DEAD");
  }

  @Test
  @DisplayName("Outbox.complete only moves a PENDING row to DONE")
  void completeIsNoOpWhenNotPending() {
    UUID order = UUID.randomUUID();
    UUID aggregate = clearCartRow(order, 0);
    String key = "CLEAR_CART:" + order;

    assertThat(outbox.complete(key)).isEqualTo(1);
    assertThat(status(aggregate)).isEqualTo("DONE");
    assertThat(outbox.complete(key)).isZero();

    UUID deadOrder = UUID.randomUUID();
    UUID dead = clearCartRow(deadOrder, 0);
    jdbc.update("update orders.outbox set status = 'DEAD' where aggregate_id = ?", dead);
    assertThat(outbox.complete("CLEAR_CART:" + deadOrder)).isZero();
    assertThat(status(dead)).isEqualTo("DEAD");
    verify(remote, never()).clearCartNow(any());
  }
}
