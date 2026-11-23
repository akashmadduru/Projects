package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.acme.ecommerce.order.client.PaymentFeignClient;
import com.acme.ecommerce.order.support.AbstractOrderIntegrationTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/** F-14 A5: the keyed, insert-only enqueue and the never-throwing tryNow. */
class WalletCreditBacksKeyedIT extends AbstractOrderIntegrationTest {

  @MockitoBean private PaymentFeignClient payments;

  @Autowired private WalletCreditBacks credits;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate tx;

  private final UUID user = UUID.randomUUID();
  private final UUID order = UUID.randomUUID();
  private String key;

  @BeforeEach
  void setUp() {
    reset(payments);
    key = "ship-cancel-wallet-" + UUID.randomUUID();
  }

  private int rows() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM orders.pending_wallet_credits WHERE idem_key = ?",
        Integer.class,
        key);
  }

  @Test
  void aReplayInsertsOneRow() {
    credits.enqueue(user, new BigDecimal("12.00"), "INR", order, key);
    credits.enqueue(user, new BigDecimal("12.00"), "INR", order, key);
    assertThat(rows()).isEqualTo(1);
  }

  @Test
  void rollbackOfTheOuterTransactionLeavesNoRow() {
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    s -> {
                      credits.enqueue(user, new BigDecimal("12.00"), "INR", order, key);
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(rows()).isZero();
  }

  @Test
  void commitKeepsTheRowAndDoesNotCallPayment() {
    tx.executeWithoutResult(s -> credits.enqueue(user, new BigDecimal("12.00"), "INR", order, key));
    assertThat(rows()).isEqualTo(1);
    verify(payments, times(0)).walletCredit(any());
  }

  @Test
  void tryNowSwallowsErrorsAndRetryDueStillDelivers() {
    credits.enqueue(user, new BigDecimal("12.00"), "INR", order, key);
    doThrow(new RuntimeException("down")).when(payments).walletCredit(any());
    credits.tryNow(key); // must not throw
    assertThat(
            jdbc.queryForObject(
                "SELECT completed_at IS NULL FROM orders.pending_wallet_credits WHERE idem_key = ?",
                Boolean.class,
                key))
        .isTrue();

    reset(payments);
    jdbc.update(
        "UPDATE orders.pending_wallet_credits SET next_attempt_at = now() - interval '1 minute'"
            + " WHERE idem_key = ?",
        key);
    credits.retryDueLocked();
    assertThat(
            jdbc.queryForObject(
                "SELECT completed_at IS NOT NULL FROM orders.pending_wallet_credits"
                    + " WHERE idem_key = ?",
                Boolean.class,
                key))
        .isTrue();
    verify(payments).walletCredit(argThat(w -> key.equals(w.idempotencyKey())));
  }

  @Test
  void tryNowDeliversAndMarksDone() {
    credits.enqueue(user, new BigDecimal("12.00"), "INR", order, key);
    credits.tryNow(key);
    verify(payments).walletCredit(argThat(w -> key.equals(w.idempotencyKey())));
    assertThat(
            jdbc.queryForObject(
                "SELECT completed_at IS NOT NULL FROM orders.pending_wallet_credits"
                    + " WHERE idem_key = ?",
                Boolean.class,
                key))
        .isTrue();
  }
}
