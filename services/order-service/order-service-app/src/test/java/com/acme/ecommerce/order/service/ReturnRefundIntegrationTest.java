package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.api.dto.AdminRequests.ReturnStatusUpdate;
import com.acme.ecommerce.order.api.dto.AdminRequests.ReturnTarget;
import com.acme.ecommerce.order.api.dto.ReturnResponse;
import com.acme.ecommerce.order.client.PaymentFeignClient;
import com.acme.ecommerce.order.client.PaymentFeignClient.RefundResult;
import com.acme.ecommerce.order.domain.Order;
import com.acme.ecommerce.order.domain.Order.ReturnLine;
import com.acme.ecommerce.order.domain.Order.ShipTo;
import com.acme.ecommerce.order.domain.OrderStatus;
import com.acme.ecommerce.order.domain.PaymentMethod;
import com.acme.ecommerce.order.domain.Pricing;
import com.acme.ecommerce.order.domain.ReturnReason;
import com.acme.ecommerce.order.event.OrderEventPublisher;
import com.acme.ecommerce.order.job.ReturnRefundWatch;
import com.acme.ecommerce.order.repository.OrderRepository;
import com.acme.ecommerce.order.repository.OrderReturnRepository;
import com.acme.ecommerce.order.support.AbstractOrderIntegrationTest;
import com.acme.ecommerce.order.web.AdminOrderController;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * O-05a on the real schema: V13, the lease and attempt updates, the finalize transaction, the watch
 * and the end-to-end refund flow. payment-service is the only thing mocked (its Feign client).
 */
class ReturnRefundIntegrationTest extends AbstractOrderIntegrationTest {

  @MockitoBean private PaymentFeignClient payments;
  @MockitoSpyBean private OrderEventPublisher events;

  @Autowired private ReturnService returns;
  @Autowired private OrderStore store;
  @Autowired private OrderRepository orders;
  @Autowired private OrderReturnRepository returnRepo;
  @Autowired private ReturnRefundWatch watch;
  @Autowired private AdminOrderController admin;
  @Autowired private JdbcTemplate jdbc;

  private final UUID product = UUID.randomUUID();

  @BeforeEach
  void clear() {
    reset(payments);
  }

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  /** A delivered CARD order of 3 x 100 with a wallet part; returns one item, PICKED_UP. */
  private UUID pickedUpReturn(String wallet) {
    Instant now = Instant.now();
    Order o =
        Order.draft(
            UUID.randomUUID(),
            "a@b.c",
            UUID.randomUUID().toString(),
            UUID.randomUUID(),
            new ShipTo("N", "1", "l1", null, "c", "s", "560001", "IN"),
            "INR",
            PaymentMethod.CARD,
            now);
    o.addItem(product, null, null, "sku", "P", new BigDecimal("100.00"), 3, null);
    BigDecimal sub = new BigDecimal("300.00");
    o.applyMoney(0, BigDecimal.ZERO, new BigDecimal(wallet), false, null);
    o.begin(
        new Pricing(
            sub,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            sub,
            BigDecimal.ZERO,
            null,
            null,
            null,
            null,
            BigDecimal.ZERO,
            false),
        null,
        null,
        now);
    o.markPaid("pay-1", now);
    o.advance(OrderStatus.DELIVERED, "delivered", null, null, now);
    orders.saveAndFlush(o);
    UUID id =
        store
            .requestReturn(
                o.getUserId(),
                o.getId(),
                List.of(new ReturnLine(product, null, 1)),
                ReturnReason.DAMAGED,
                null,
                "SOURCE",
                null)
            .ret()
            .id();
    store.decide(id, true, null);
    store.pickedUp(id);
    return id;
  }

  /** Another picked-up return of one item on the same order. */
  private UUID siblingOf(UUID returnId) {
    UUID orderId = orderOf(returnId);
    UUID user =
        jdbc.queryForObject("select user_id from orders.orders where id = ?", UUID.class, orderId);
    UUID id =
        store
            .requestReturn(
                user,
                orderId,
                List.of(new ReturnLine(product, null, 1)),
                ReturnReason.DAMAGED,
                null,
                "SOURCE",
                null)
            .ret()
            .id();
    store.decide(id, true, null);
    store.pickedUp(id);
    return id;
  }

  private void paymentReplies(String... statuses) {
    var stub = when(payments.refund(any(), any()));
    RefundResult first = new RefundResult("PARTIALLY_REFUNDED", BigDecimal.TEN, statuses[0]);
    var chain = stub.thenReturn(first);
    for (int i = 1; i < statuses.length; i++) {
      chain = chain.thenReturn(new RefundResult("PARTIALLY_REFUNDED", BigDecimal.TEN, statuses[i]));
    }
  }

  private String status(UUID id) {
    return jdbc.queryForObject(
        "select status from orders.order_returns where id = ?", String.class, id);
  }

  private long outbox(UUID orderId, String kind) {
    return jdbc.queryForObject(
        "select count(*) from orders.outbox where aggregate_id = ? and kind = ?",
        Long.class,
        orderId,
        kind);
  }

  private UUID orderOf(UUID returnId) {
    return jdbc.queryForObject(
        "select order_id from orders.order_returns where id = ?", UUID.class, returnId);
  }

  private void verifyWalletCredits(int times, UUID id) {
    verify(payments, times(times))
        .walletCredit(argThat(c -> c.idempotencyKey().equals("return-wallet-" + id)));
  }

  // ---- T2: migration, lease, attempt ----------------------------------------------------

  @Test
  @DisplayName("V13 applies on top of V12 and a row with null legs loads with defaults")
  void migrationAndNullLegs() {
    UUID id = pickedUpReturn("0");

    var loaded = returnRepo.findById(id).orElseThrow();

    assertThat(loaded.getWalletLegAmount()).isNull();
    assertThat(loaded.getRestLegState()).isNull();
    assertThat(loaded.getRestLegAttempt()).isZero();
    assertThat(loaded.getShippingRefund()).isEqualByComparingTo("0");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_indexes where indexname = 'ix_order_returns_rest_pending'",
                Long.class))
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("the lease is won once, is refused to a second caller, and frees on release")
  void leaseWinsOnce() {
    UUID id = pickedUpReturn("0");

    java.time.Instant mine = store.claimRefundLease(id);
    assertThat(mine).isNotNull();
    assertThat(store.claimRefundLease(id)).isNull();
    store.releaseRefundLease(id, mine);
    assertThat(store.claimRefundLease(id)).isNotNull();
  }

  @Test
  @DisplayName("a holder whose lease expired cannot free the lease the next caller took")
  void releaseIsHolderScoped() {
    UUID id = pickedUpReturn("0");
    java.time.Instant stale = store.claimRefundLease(id);
    jdbc.update(
        "update orders.order_returns set refund_lease_until = now() - interval '1 second'"
            + " where id = ?",
        id);
    java.time.Instant next = store.claimRefundLease(id);
    assertThat(next).isNotNull();

    store.releaseRefundLease(id, stale);

    assertThat(store.claimRefundLease(id)).isNull();
    store.releaseRefundLease(id, next);
    assertThat(store.claimRefundLease(id)).isNotNull();
  }

  @Test
  @DisplayName("the lease is refused unless the return is PICKED_UP")
  void leaseNeedsPickedUp() {
    UUID id = pickedUpReturn("0");
    jdbc.update("update orders.order_returns set status = 'REFUNDED' where id = ?", id);

    assertThat(store.claimRefundLease(id)).isNull();
  }

  @Test
  @DisplayName("the attempt bump is conditional on the current attempt")
  void bumpIsConditional() {
    UUID id = pickedUpReturn("0");

    assertThat(store.bumpRestLegAttempt(id, 0)).isTrue();
    assertThat(store.bumpRestLegAttempt(id, 0)).isFalse();
    assertThat(store.bumpRestLegAttempt(id, 1)).isTrue();
    assertThat(returnRepo.findById(id).orElseThrow().getRestLegAttempt()).isEqualTo(2);
  }

  // ---- T6 / T9: flow ----------------------------------------------------------------------

  @Test
  @DisplayName("a split refund finalizes once: REFUNDED, one RELEASE_STOCK, one event row")
  void finalizesOnce() {
    UUID id = pickedUpReturn("30");
    UUID orderId = orderOf(id);
    paymentReplies("SUCCEEDED");

    ReturnResponse res = returns.refund(id);

    assertThat(res.status()).isEqualTo("REFUNDED");
    assertThat(status(id)).isEqualTo("REFUNDED");
    assertThat(outbox(orderId, "RELEASE_STOCK")).isEqualTo(1);
    assertThat(outbox(orderId, "ORDER_EVENT")).isEqualTo(1);
    verifyWalletCredits(1, id);
    assertThat(
            jdbc.queryForObject(
                "select wallet_leg_amount from orders.order_returns where id = ?",
                BigDecimal.class,
                id))
        .isEqualByComparingTo("10.00");
  }

  @Test
  @DisplayName("AC-9: calling refund again on a REFUNDED return adds no call, ledger or outbox row")
  void refundedIsNoOp() {
    UUID id = pickedUpReturn("30");
    UUID orderId = orderOf(id);
    paymentReplies("SUCCEEDED");
    returns.refund(id);
    reset(payments);

    ReturnResponse again = returns.refund(id);

    assertThat(again.status()).isEqualTo("REFUNDED");
    org.mockito.Mockito.verifyNoInteractions(payments);
    assertThat(outbox(orderId, "RELEASE_STOCK")).isEqualTo(1);
    assertThat(outbox(orderId, "ORDER_EVENT")).isEqualTo(1);
  }

  @Test
  @DisplayName("AC-8: wallet ok, card fails, retry: one wallet credit, same card key, REFUNDED")
  void walletOkCardFailsThenRetry() {
    UUID id = pickedUpReturn("30");
    UUID orderId = orderOf(id);
    when(payments.refund(any(), any()))
        .thenThrow(new IllegalStateException("card down"))
        .thenReturn(new RefundResult("PARTIALLY_REFUNDED", BigDecimal.TEN, "SUCCEEDED"));

    assertThatThrownBy(() -> returns.refund(id)).isInstanceOf(IllegalStateException.class);
    assertThat(status(id)).isEqualTo("PICKED_UP");
    assertThat(
            jdbc.queryForObject(
                "select rest_leg_state from orders.order_returns where id = ?", String.class, id))
        .isEqualTo("FAILED");
    assertThat(
            jdbc.queryForObject(
                "select wallet_leg_done_at is not null from orders.order_returns where id = ?",
                Boolean.class,
                id))
        .isTrue();
    assertThat(outbox(orderId, "RELEASE_STOCK")).isZero();

    returns.refund(id);

    assertThat(status(id)).isEqualTo("REFUNDED");
    verifyWalletCredits(1, id);
    verify(payments, times(2))
        .refund(any(), argThat(r -> r.idempotencyKey().equals("return-" + id)));
    assertThat(outbox(orderId, "RELEASE_STOCK")).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "a row with no recorded leg progress retries on the same keys (wallet replayed once)")
  void preMigrationRowRetries() {
    UUID id = pickedUpReturn("30");
    paymentReplies("SUCCEEDED");

    // Run the refund once (the wallet credit and card refund happen), then erase the progress
    // columns so the row looks like one an old build left PICKED_UP: money moved, nothing recorded.
    returns.refund(id);
    jdbc.update(
        "update orders.order_returns set status = 'PICKED_UP', wallet_leg_amount = null,"
            + " rest_leg_amount = null, rest_route = null, wallet_leg_done_at = null,"
            + " rest_leg_state = null where id = ?",
        id);
    reset(payments);
    paymentReplies("SUCCEEDED");

    returns.refund(id);

    // The wallet call repeats once under the unchanged key (payment-service replays it).
    verifyWalletCredits(1, id);
    assertThat(status(id)).isEqualTo("REFUNDED");
  }

  @Test
  @DisplayName("a failing finalize rolls back: no REFUNDED, no stock or event outbox rows")
  void finalizeRollsBack() {
    UUID id = pickedUpReturn("0");
    UUID orderId = orderOf(id);
    paymentReplies("SUCCEEDED");
    doThrow(new IllegalStateException("publish failed"))
        .when(events)
        .publishReturn(any(), any(), any());

    assertThatThrownBy(() -> returns.refund(id)).isInstanceOf(IllegalStateException.class);

    assertThat(status(id)).isEqualTo("PICKED_UP");
    assertThat(outbox(orderId, "RELEASE_STOCK")).isZero();
    assertThat(outbox(orderId, "ORDER_EVENT")).isZero();
  }

  @Test
  @DisplayName(
      "C5: two concurrent refunds give one REFUNDED, one stock row, one event, one card call")
  void concurrentRefunds() throws Exception {
    UUID id = pickedUpReturn("0");
    UUID orderId = orderOf(id);
    CountDownLatch inCall = new CountDownLatch(1);
    CountDownLatch go = new CountDownLatch(1);
    when(payments.refund(any(), any()))
        .thenAnswer(
            i -> {
              inCall.countDown();
              go.await(10, TimeUnit.SECONDS);
              return new RefundResult("PARTIALLY_REFUNDED", BigDecimal.TEN, "SUCCEEDED");
            });
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<ReturnResponse> first = pool.submit(() -> returns.refund(id));
      assertThat(inCall.await(10, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> returns.refund(id))
          .isInstanceOfSatisfying(
              CodedException.class,
              e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
      go.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo("REFUNDED");
    } finally {
      pool.shutdownNow();
    }

    assertThat(status(id)).isEqualTo("REFUNDED");
    assertThat(outbox(orderId, "RELEASE_STOCK")).isEqualTo(1);
    assertThat(outbox(orderId, "ORDER_EVENT")).isEqualTo(1);
    verify(payments, times(1)).refund(any(), any());
  }

  @Test
  @DisplayName("two sibling returns refunded at once freeze legs that stay inside the order totals")
  void concurrentSiblings() throws Exception {
    // 3 x 100, wallet 200: each unlocked share is 66.67, three of them (200.01) break the cap.
    UUID first = pickedUpReturn("200");
    UUID second = siblingOf(first);
    UUID third = siblingOf(first);
    paymentReplies("SUCCEEDED");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
      List<Future<ReturnResponse>> done =
          List.of(first, second, third).stream()
              .map(
                  id ->
                      pool.submit(
                          () -> {
                            start.await();
                            return returns.refund(id);
                          }))
              .toList();
      start.countDown();
      for (Future<ReturnResponse> f : done) {
        assertThat(f.get(20, TimeUnit.SECONDS).status()).isEqualTo("REFUNDED");
      }
    } finally {
      pool.shutdownNow();
    }

    for (UUID id : List.of(first, second, third)) {
      assertThat(
              jdbc.queryForObject(
                  "select wallet_leg_amount + rest_leg_amount - refund_amount from"
                      + " orders.order_returns where id = ?",
                  BigDecimal.class,
                  id))
          .isEqualByComparingTo("0");
    }
    // Wallet W = 200 and card T - W = 100 are never exceeded by the summed legs.
    assertThat(
            jdbc.queryForObject(
                "select sum(wallet_leg_amount) from orders.order_returns where order_id = ?",
                BigDecimal.class,
                orderOf(first)))
        .isLessThanOrEqualTo(new BigDecimal("200.00"));
    assertThat(
            jdbc.queryForObject(
                "select sum(rest_leg_amount) from orders.order_returns where order_id = ?",
                BigDecimal.class,
                orderOf(first)))
        .isLessThanOrEqualTo(new BigDecimal("100.00"));
  }

  @Test
  @DisplayName("a second finalize on a REFUNDED return conflicts and writes nothing")
  void secondFinalizeConflicts() {
    UUID id = pickedUpReturn("0");
    paymentReplies("SUCCEEDED");
    returns.refund(id);

    assertThatThrownBy(() -> store.completeRefund(id)).isInstanceOf(CodedException.class);
  }

  // ---- T8: watch ----------------------------------------------------------------------------

  @Test
  @DisplayName("AC-11/12: PENDING leaves PICKED_UP; the watch finalizes it once when SUCCEEDED")
  void watchSettlesPending() {
    UUID id = pickedUpReturn("0");
    UUID orderId = orderOf(id);
    paymentReplies("PENDING", "SUCCEEDED");

    ReturnResponse res = returns.refund(id);

    assertThat(res.status()).isEqualTo("PICKED_UP");
    assertThat(res.refundState()).isEqualTo("PENDING");
    assertThat(outbox(orderId, "RELEASE_STOCK")).isZero();

    watch.poll();
    watch.poll();

    assertThat(status(id)).isEqualTo("REFUNDED");
    assertThat(outbox(orderId, "RELEASE_STOCK")).isEqualTo(1);
    assertThat(outbox(orderId, "ORDER_EVENT")).isEqualTo(1);
    verify(payments, times(2)).refund(any(), any());
  }

  @Test
  @DisplayName("AC-12: FAILED stays PICKED_UP; the admin retry skips the wallet and uses -r1")
  void failedThenAdminRetry() {
    UUID id = pickedUpReturn("30");
    paymentReplies("PENDING", "FAILED", "FAILED", "SUCCEEDED");

    returns.refund(id);
    watch.poll();

    assertThat(status(id)).isEqualTo("PICKED_UP");
    assertThat(
            jdbc.queryForObject(
                "select rest_leg_state from orders.order_returns where id = ?", String.class, id))
        .isEqualTo("FAILED");

    // Admin retry: replay of the old key says FAILED, so the -r1 key is used.
    returns.refund(id);

    assertThat(status(id)).isEqualTo("REFUNDED");
    verifyWalletCredits(1, id);
    verify(payments).refund(any(), argThat(r -> r.idempotencyKey().equals("return-" + id + "-r1")));
    verify(payments, never())
        .refund(any(), argThat(r -> r.idempotencyKey().equals("return-" + id + "-r2")));
  }

  // ---- T9: admin only ---------------------------------------------------------------------

  @Test
  @DisplayName("a customer cannot trigger a return refund")
  void customerForbidden() {
    UUID id = pickedUpReturn("0");
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "u", "n/a", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));

    assertThatThrownBy(() -> admin.returnStatus(id, new ReturnStatusUpdate(ReturnTarget.REFUNDED)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    org.mockito.Mockito.verifyNoInteractions(payments);
  }
}
