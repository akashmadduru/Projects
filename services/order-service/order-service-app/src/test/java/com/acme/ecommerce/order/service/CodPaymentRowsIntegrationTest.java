package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.order.client.PaymentFeignClient;
import com.acme.ecommerce.order.client.PaymentFeignClient.CreatePayment;
import com.acme.ecommerce.order.client.PaymentFeignClient.PaymentRow;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.outbox.OutboxRelay;
import com.acme.ecommerce.order.service.CodPaymentRows.Outcome;
import com.acme.ecommerce.order.support.AbstractOrderIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The COD payment row ensure (B-10, PR-A): idempotent, never changes a foreign row. */
class CodPaymentRowsIntegrationTest extends AbstractOrderIntegrationTest {

  private static final String PAYMENT_ID = "0b1f6a52-5d3e-4a53-9a39-5b6f3c1d2e10";

  @MockitoBean private PaymentFeignClient payments;
  @MockitoBean private OrderRemote remote;
  @MockitoBean private OrderEventPublisher events;

  @Autowired private CodPaymentRows codRows;
  @Autowired private OutboxRelay relay;
  @Autowired private com.acme.ecommerce.order.outbox.Outbox outbox;
  @Autowired private JdbcTemplate jdbc;

  private UUID seed(String method, String status, String total, String wallet) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into orders.orders (id, user_id, status, currency, total_amount, idempotency_key,"
            + " address_id, ship_full_name, ship_line1, ship_city, ship_postal_code, ship_country,"
            + " subtotal_amount, payment_method, wallet_amount)"
            + " values (?, ?, ?, 'INR', ?::numeric, ?, ?, 'T', '1 Road', 'City', '560001', 'IN',"
            + " ?::numeric, ?, ?::numeric)",
        id,
        UUID.randomUUID(),
        status,
        total,
        "seed-" + id,
        UUID.randomUUID(),
        total,
        method,
        wallet);
    return id;
  }

  private String paymentIdOf(UUID id) {
    return jdbc.queryForObject(
        "select payment_id from orders.orders where id = ?", String.class, id);
  }

  private void rowReturned(String method, String amount) {
    when(payments.ensureRow(any()))
        .thenReturn(new PaymentRow(PAYMENT_ID, "COD_PENDING", method, new BigDecimal(amount)));
  }

  @Test
  @DisplayName("C1: ensure twice gives CREATED then EXISTING, one remote call and one attach")
  void ensureTwice() {
    UUID id = seed("COD", "PLACED", "500.00", "120.00");
    rowReturned("COD", "380.00");

    assertThat(codRows.ensure(id)).isEqualTo(Outcome.CREATED);
    assertThat(codRows.ensure(id)).isEqualTo(Outcome.EXISTING);

    ArgumentCaptor<CreatePayment> sent = ArgumentCaptor.forClass(CreatePayment.class);
    verify(payments, times(1)).ensureRow(sent.capture());
    assertThat(sent.getValue().orderId()).isEqualTo(id);
    assertThat(sent.getValue().amount()).isEqualByComparingTo("380.00");
    assertThat(sent.getValue().currency()).isEqualTo("INR");
    assertThat(sent.getValue().method()).isEqualTo("COD");
    assertThat(paymentIdOf(id)).isEqualTo(PAYMENT_ID);
  }

  @Test
  @DisplayName("a cancelled (abandoned) order is skipped without a remote call")
  void cancelledSkipped() {
    UUID id = seed("COD", "CANCELLED", "500.00", "0");

    assertThat(codRows.ensure(id)).isEqualTo(Outcome.SKIPPED_CANCELLED);

    verify(payments, never()).ensureRow(any());
    assertThat(paymentIdOf(id)).isNull();
  }

  @Test
  @DisplayName("a non-COD order is skipped")
  void notCodSkipped() {
    UUID id = seed("CARD", "PLACED", "500.00", "0");

    assertThat(codRows.ensure(id)).isEqualTo(Outcome.SKIPPED_NOT_COD);

    verify(payments, never()).ensureRow(any());
  }

  @Test
  @DisplayName("a CARD row found for the order is reported and never attached")
  void cardRowConflict() {
    UUID id = seed("COD", "PLACED", "500.00", "0");
    rowReturned("CARD", "500.00");

    assertThat(codRows.ensure(id)).isEqualTo(Outcome.CONFLICT_METHOD);

    assertThat(paymentIdOf(id)).isNull();
  }

  @Test
  @DisplayName("a wallet that covers the total leaves no remainder: no call, ERROR outcome")
  void zeroRemainder() {
    UUID id = seed("COD", "PLACED", "500.00", "500.00");

    assertThat(codRows.ensure(id)).isEqualTo(Outcome.SKIPPED_BAD_AMOUNT);

    verify(payments, never()).ensureRow(any());
    assertThat(paymentIdOf(id)).isNull();
  }

  @Test
  @DisplayName("an existing row with another amount wins: it is attached, the order is not touched")
  void existingRowAmountWins() {
    UUID id = seed("COD", "PLACED", "500.00", "0");
    rowReturned("COD", "450.00");

    assertThat(codRows.ensure(id)).isEqualTo(Outcome.CREATED);

    assertThat(paymentIdOf(id)).isEqualTo(PAYMENT_ID);
    assertThat(
            jdbc.queryForObject(
                "select total_amount from orders.orders where id = ?", BigDecimal.class, id))
        .isEqualByComparingTo("500.00");
  }

  @Test
  @DisplayName("a transient remote error propagates so the relay retries")
  void transientErrorThrows() {
    UUID id = seed("COD", "PLACED", "500.00", "0");
    when(payments.ensureRow(any())).thenThrow(new IllegalStateException("payment-service down"));

    assertThatThrownBy(() -> codRows.ensure(id)).hasMessageContaining("down");

    assertThat(paymentIdOf(id)).isNull();
  }

  @Test
  @DisplayName("AC-3: the relay creates the missing row once; a second pass makes no call")
  void relayCreatesRowOnce() {
    UUID id = seed("COD", "PLACED", "500.00", "120.00");
    rowReturned("COD", "380.00");
    codRows.enqueueRepair(id);
    jdbc.update(
        "update orders.outbox set next_attempt_at = now() where idem_key = ?",
        "ENSURE_COD_PAYMENT:" + id);

    relay.relay();
    relay.relay();

    verify(payments, times(1)).ensureRow(argThat(r -> id.equals(r.orderId())));
    assertThat(paymentIdOf(id)).isEqualTo(PAYMENT_ID);
    assertThat(outboxStatus(id)).isEqualTo("DONE");
  }

  @Test
  @DisplayName("AC-3: a repair for an abandoned order goes DONE with no remote call")
  void relayAbandonedOrder() {
    UUID id = seed("COD", "CANCELLED", "500.00", "0");
    codRows.enqueueRepair(id);
    jdbc.update(
        "update orders.outbox set next_attempt_at = now() where idem_key = ?",
        "ENSURE_COD_PAYMENT:" + id);

    relay.relay();

    verify(payments, never()).ensureRow(argThat(r -> id.equals(r.orderId())));
    assertThat(outboxStatus(id)).isEqualTo("DONE");
  }

  @Test
  @DisplayName("AC-2 plumbing: the repair row waits at least 55 s and repairDone completes it")
  void repairRowIsDelayedAndCompletable() {
    UUID id = seed("COD", "PLACED", "500.00", "0");
    codRows.enqueueRepair(id);

    assertThat(outboxStatus(id)).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "select extract(epoch from (next_attempt_at - now())) from orders.outbox"
                    + " where idem_key = ?",
                Double.class,
                "ENSURE_COD_PAYMENT:" + id))
        .isBetween(55.0, 60.5);

    codRows.repairDone(id);

    assertThat(outboxStatus(id)).isEqualTo("DONE");
  }

  // ---- PR-B: collect, legacy rowless, concurrency, non-blocking -------------------------------

  private void collected(UUID id) {
    jdbc.update(
        "update orders.orders set status = 'DELIVERED', payment_status = 'COD_COLLECTED',"
            + " delivered_at = now() where id = ?",
        id);
  }

  private void dueNow(String kind, UUID id) {
    jdbc.update(
        "update orders.outbox set next_attempt_at = now() where idem_key = ?", kind + ":" + id);
  }

  @Test
  @DisplayName("AC-6/7: a collected rowless legacy order: ensure with its figures, then collect")
  void legacyRowlessCollected() {
    UUID id = seed("COD", "PLACED", "500.00", "120.00");
    collected(id);
    rowReturned("COD", "380.00");

    assertThat(codRows.collect(id)).isEqualTo(Outcome.CREATED);

    ArgumentCaptor<CreatePayment> sent = ArgumentCaptor.forClass(CreatePayment.class);
    verify(payments, times(1)).ensureRow(sent.capture());
    assertThat(sent.getValue().amount()).isEqualByComparingTo("380.00");
    assertThat(sent.getValue().method()).isEqualTo("COD");
    assertThat(paymentIdOf(id)).isEqualTo(PAYMENT_ID);
    verify(remote, times(1)).codCollectedNow(id);
    // redelivery: the row exists, no second ensure call
    assertThat(codRows.collect(id)).isEqualTo(Outcome.EXISTING);
    verify(payments, times(1)).ensureRow(argThat(r -> id.equals(r.orderId())));
  }

  @Test
  @DisplayName("AC-7: a COD_PENDING (undelivered) legacy order gets a row but is never collected")
  void undeliveredNotCollected() {
    UUID id = seed("COD", "PLACED", "500.00", "0");
    jdbc.update("update orders.orders set payment_status = 'COD_PENDING' where id = ?", id);
    rowReturned("COD", "500.00");

    assertThat(codRows.collect(id)).isEqualTo(Outcome.CREATED);

    assertThat(paymentIdOf(id)).isEqualTo(PAYMENT_ID);
    verify(remote, never()).codCollectedNow(id);
  }

  @Test
  @DisplayName("AC-9: a cancelled order gets no row and no collected mark")
  void cancelledNotCollected() {
    UUID id = seed("COD", "CANCELLED", "500.00", "0");

    assertThat(codRows.collect(id)).isEqualTo(Outcome.SKIPPED_CANCELLED);

    verify(payments, never()).ensureRow(argThat(r -> id.equals(r.orderId())));
    verify(remote, never()).codCollectedNow(id);
  }

  @Test
  @DisplayName("AC-11: a re-armed COD_COLLECTED row ensures the row, then marks it collected")
  void relayCollect() {
    UUID id = seed("COD", "PLACED", "500.00", "0");
    collected(id);
    rowReturned("COD", "500.00");
    outbox.enqueue(com.acme.ecommerce.order.outbox.Outbox.Kind.COD_COLLECTED, id);
    dueNow("COD_COLLECTED", id);

    relay.relay();
    relay.relay();

    verify(payments, times(1)).ensureRow(argThat(r -> id.equals(r.orderId())));
    verify(remote, times(1)).codCollectedNow(id);
    assertThat(
            jdbc.queryForObject(
                "select status from orders.outbox where idem_key = ?",
                String.class,
                "COD_COLLECTED:" + id))
        .isEqualTo("DONE");
  }

  @Test
  @DisplayName("AC-8: two threads ensuring one order: one CREATED, one EXISTING, one attach")
  void twoThreads() throws Exception {
    UUID id = seed("COD", "PLACED", "500.00", "0");
    rowReturned("COD", "500.00");
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    var go = new java.util.concurrent.CountDownLatch(1);
    try {
      var a =
          pool.submit(
              () -> {
                go.await();
                return codRows.ensure(id);
              });
      var b =
          pool.submit(
              () -> {
                go.await();
                return codRows.ensure(id);
              });
      go.countDown();
      assertThat(
              List.of(
                  a.get(10, java.util.concurrent.TimeUnit.SECONDS),
                  b.get(10, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(Outcome.CREATED, Outcome.EXISTING);
    } finally {
      pool.shutdownNow();
    }
    assertThat(paymentIdOf(id)).isEqualTo(PAYMENT_ID);
  }

  @Test
  @DisplayName("AC-14: an ensure blocked on payment-service holds no transaction or row lock")
  void blockedEnsureDoesNotBlockOthers() throws Exception {
    UUID slow = seed("COD", "PLACED", "500.00", "0");
    UUID other = seed("COD", "PLACED", "300.00", "0");
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    when(payments.ensureRow(any()))
        .thenAnswer(
            i -> {
              CreatePayment c = i.getArgument(0);
              if (c.orderId().equals(slow)) {
                entered.countDown();
                release.await();
              }
              return new PaymentRow(
                  c.orderId().equals(slow) ? PAYMENT_ID : "1c1f6a52-5d3e-4a53-9a39-5b6f3c1d2e11",
                  "COD_PENDING",
                  "COD",
                  c.amount());
            });
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var blocked = pool.submit(() -> codRows.ensure(slow));
      assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

      var meanwhile =
          pool.submit(
              () -> {
                jdbc.update(
                    "update orders.orders set total_amount = total_amount where id = ?", slow);
                return codRows.ensure(other);
              });
      assertThat(meanwhile.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(Outcome.CREATED);
      assertThat(blocked.isDone()).isFalse();
      release.countDown();
      assertThat(blocked.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(Outcome.CREATED);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("AC-14: two repair rows, one failing: the other still goes DONE")
  void oneFailingRepairDoesNotBlockTheOther() {
    UUID bad = seed("COD", "PLACED", "500.00", "0");
    UUID good = seed("COD", "PLACED", "300.00", "0");
    when(payments.ensureRow(any()))
        .thenAnswer(
            i -> {
              CreatePayment c = i.getArgument(0);
              if (c.orderId().equals(bad)) {
                throw new IllegalStateException("payment-service down");
              }
              return new PaymentRow(PAYMENT_ID, "COD_PENDING", "COD", c.amount());
            });
    for (UUID id : List.of(bad, good)) {
      codRows.enqueueRepair(id);
      dueNow("ENSURE_COD_PAYMENT", id);
    }

    relay.relay();

    assertThat(outboxStatus(good)).isEqualTo("DONE");
    assertThat(paymentIdOf(good)).isEqualTo(PAYMENT_ID);
    assertThat(outboxStatus(bad)).isEqualTo("PENDING");
    assertThat(paymentIdOf(bad)).isNull();
  }

  private String outboxStatus(UUID id) {
    return jdbc.queryForObject(
        "select status from orders.outbox where idem_key = ?",
        String.class,
        "ENSURE_COD_PAYMENT:" + id);
  }
}
