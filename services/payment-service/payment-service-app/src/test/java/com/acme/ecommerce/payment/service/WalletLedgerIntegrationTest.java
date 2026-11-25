package com.acme.ecommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.payment.support.AbstractPaymentIntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Wallet ledger on a real database (TD-P8-01): idempotency, overdraft, currency, reversal. */
class WalletLedgerIntegrationTest extends AbstractPaymentIntegrationTest {

  @Autowired private WalletService wallet;

  private static ErrorCode codeOf(Throwable t) {
    return ((CodedException) t).errorCode();
  }

  @Test
  @DisplayName("a replayed credit or debit key moves money once")
  void idempotentByKey() {
    UUID user = UUID.randomUUID();
    String k = "credit-" + UUID.randomUUID();

    wallet.credit(user, new BigDecimal("100.00"), "INR", "REFUND", "r1", k);
    wallet.credit(user, new BigDecimal("100.00"), "INR", "REFUND", "r1", k);
    assertThat(wallet.balance(user).balance()).isEqualByComparingTo("100.00");

    String d = "debit-" + UUID.randomUUID();
    UUID order = UUID.randomUUID();
    wallet.debit(user, new BigDecimal("40.00"), "INR", order, d);
    wallet.debit(user, new BigDecimal("40.00"), "INR", order, d);
    assertThat(wallet.balance(user).balance()).isEqualByComparingTo("60.00");
  }

  @Test
  @DisplayName("a debit beyond the balance, or in another currency, is refused and moves nothing")
  void noOverdraftNoCurrencyMix() {
    UUID user = UUID.randomUUID();
    wallet.credit(user, new BigDecimal("30.00"), "INR", "ADJUSTMENT", null, "c-" + user);

    assertThatThrownBy(
            () ->
                wallet.debit(user, new BigDecimal("30.01"), "INR", UUID.randomUUID(), "d1-" + user))
        .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));
    assertThatThrownBy(
            () ->
                wallet.debit(user, new BigDecimal("1.00"), "USD", UUID.randomUUID(), "d2-" + user))
        .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));
    assertThatThrownBy(
            () -> wallet.credit(user, new BigDecimal("5.00"), "USD", "REFUND", null, "c2-" + user))
        .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.CURRENCY_MISMATCH));
    assertThat(wallet.balance(user).balance()).isEqualByComparingTo("30.00");
  }

  @Test
  @DisplayName("concurrent debits never take the balance below zero")
  void concurrentDebitsDoNotOverdraw() throws Exception {
    UUID user = UUID.randomUUID();
    wallet.credit(user, new BigDecimal("100.00"), "INR", "ADJUSTMENT", null, "c-" + user);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Future<Boolean>> results = new ArrayList<>();
    try {
      for (int i = 0; i < 8; i++) {
        String key = "d-" + user + "-" + i;
        results.add(
            pool.submit(
                () -> {
                  try {
                    wallet.debit(user, new BigDecimal("30.00"), "INR", UUID.randomUUID(), key);
                    return true;
                  } catch (CodedException e) {
                    return false;
                  }
                }));
      }
      int ok = 0;
      for (Future<Boolean> f : results) {
        ok += f.get() ? 1 : 0;
      }
      assertThat(ok).isEqualTo(3);
      assertThat(wallet.balance(user).balance()).isEqualByComparingTo("10.00");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "reversing a debit returns exactly its amount once; an unknown debit returns nothing")
  void reverseDebitOnce() {
    UUID user = UUID.randomUUID();
    wallet.credit(user, new BigDecimal("50.00"), "INR", "ADJUSTMENT", null, "c-" + user);
    String debitKey = "d-" + user;
    wallet.debit(user, new BigDecimal("20.00"), "INR", UUID.randomUUID(), debitKey);

    assertThat(wallet.reverseDebit(user, debitKey, "rev-" + user)).isTrue();
    assertThat(wallet.reverseDebit(user, debitKey, "rev-" + user)).isFalse();
    assertThat(wallet.reverseDebit(user, "never-happened", "rev2-" + user)).isFalse();
    assertThat(wallet.balance(user).balance()).isEqualByComparingTo("50.00");
  }
}
