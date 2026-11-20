package com.acme.ecommerce.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.acme.ecommerce.order.event.OrderEvent;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.event.OrderEventType;
import com.acme.ecommerce.order.outbox.Outbox.Kind;
import com.acme.ecommerce.order.service.OrderRemote;
import com.acme.ecommerce.order.support.AbstractOrderIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/** TD-P6-01: outbox rows commit with their transaction and are delivered with retries, in order. */
class OutboxIntegrationTest extends AbstractOrderIntegrationTest {

  @MockitoBean private OrderRemote remote;
  @MockitoBean private OrderEventPublisher events;

  @Autowired private Outbox outbox;
  @Autowired private OutboxRelay relay;
  @Autowired private TransactionTemplate tx;
  @Autowired private JdbcTemplate jdbc;

  private final UUID order = UUID.randomUUID();

  private List<String> statuses() {
    return jdbc.queryForList(
        "select status from orders.outbox where aggregate_id = ? order by seq",
        String.class,
        order);
  }

  private int attempts(Kind kind) {
    return jdbc.queryForObject(
        "select attempts from orders.outbox where aggregate_id = ? and kind = ?",
        Integer.class,
        order,
        kind.name());
  }

  @Test
  @DisplayName("a row written in a transaction that rolls back never exists")
  void rolledBackWithItsTransaction() {
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      outbox.enqueue(Kind.RESTORE_STOCK, order);
                      throw new IllegalStateException("order change failed");
                    }))
        .hasMessage("order change failed");

    assertThat(statuses()).isEmpty();
  }

  @Test
  @DisplayName("due rows are delivered through the matching call and marked DONE")
  void deliversAndMarksDone() {
    outbox.enqueue(Kind.RESTORE_STOCK, order);
    outbox.enqueue(Kind.COD_COLLECTED, order);

    relay.relay();

    verify(remote).restoreStockNow(order);
    verify(remote).codCollectedNow(order);
    assertThat(statuses()).containsExactly("DONE", "DONE");
  }

  @Test
  @DisplayName("the same effect requested twice is recorded once")
  void deduplicated() {
    outbox.enqueue(Kind.RELEASE_COUPON, order);
    outbox.enqueue(Kind.RELEASE_COUPON, order);

    assertThat(statuses()).hasSize(1);
  }

  @Test
  @DisplayName("a failure is retried later, not straight away")
  void failureBacksOff() {
    doThrow(new IllegalStateException("inventory down")).when(remote).restoreStockNow(order);
    outbox.enqueue(Kind.RESTORE_STOCK, order);

    relay.relay();
    relay.relay();

    assertThat(statuses()).containsExactly("PENDING");
    assertThat(attempts(Kind.RESTORE_STOCK)).isEqualTo(1);
    Instant next =
        jdbc.queryForObject(
                "select next_attempt_at from orders.outbox where aggregate_id = ?",
                java.sql.Timestamp.class,
                order)
            .toInstant();
    assertThat(next).isAfter(Instant.now());
  }

  @Test
  @DisplayName("one order's rows wait behind an older row that is still failing")
  void perOrderOrdering() {
    doThrow(new IllegalStateException("inventory down")).when(remote).restoreStockNow(order);
    outbox.enqueue(Kind.RESTORE_STOCK, order);
    outbox.enqueue(Kind.COD_COLLECTED, order);

    relay.relay();

    verify(remote, never()).codCollectedNow(any());
    assertThat(statuses()).containsExactly("PENDING", "PENDING");
  }

  @Test
  @DisplayName("after the last allowed attempt the row is DEAD and no longer blocks the order")
  void deadAfterMaxAttempts() {
    doThrow(new IllegalStateException("inventory down")).when(remote).restoreStockNow(order);
    outbox.enqueue(Kind.RESTORE_STOCK, order);
    outbox.enqueue(Kind.COD_COLLECTED, order);
    jdbc.update(
        "update orders.outbox set attempts = ? where aggregate_id = ? and kind = 'RESTORE_STOCK'",
        OutboxRelay.MAX_ATTEMPTS - 1,
        order);

    relay.relay();
    relay.relay();

    assertThat(statuses()).containsExactly("DEAD", "DONE");
    verify(remote).codCollectedNow(order);
  }

  @Test
  @DisplayName("an event comes back from the outbox exactly as it was recorded")
  void eventRoundTrip() {
    UUID eventId = UUID.randomUUID();
    OrderEvent event =
        new OrderEvent(
            eventId,
            OrderEventType.ORDER_CANCELLED,
            order,
            UUID.randomUUID(),
            "c@example.com",
            "CANCELLED",
            "CARD",
            new BigDecimal("998.00"),
            "INR",
            null,
            null,
            null,
            2,
            "Shirt",
            Instant.now().toString(),
            "FAILED",
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null);
    outbox.enqueue(Kind.ORDER_EVENT, order, event, "event:" + eventId);

    relay.relay();

    ArgumentCaptor<OrderEvent> sent = ArgumentCaptor.forClass(OrderEvent.class);
    verify(events).sendNow(eq(order), sent.capture());
    assertThat(sent.getValue()).usingRecursiveComparison().isEqualTo(event);
  }
}
