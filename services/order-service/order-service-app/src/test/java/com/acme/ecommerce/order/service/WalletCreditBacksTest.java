package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.order.client.PaymentFeignClient;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** TD-P8-07: wallet money owed back is recorded first and retried until it lands. */
class WalletCreditBacksTest {

  private final UUID order = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();
  private JdbcClient jdbc;
  private PaymentFeignClient payments;
  private WalletCreditBacks credits;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    payments = mock(PaymentFeignClient.class);
    credits = new WalletCreditBacks(jdbc, payments, mock(JobLock.class));
    when(jdbc.sql(startsWith("SELECT user_id"))
            .param(anyString(), any())
            .query(any(RowMapper.class))
            .optional())
        .thenReturn(
            Optional.of(
                new WalletCreditBacks.Pending(user, new BigDecimal("150.00"), "INR", order, 0)));
  }

  @Test
  @DisplayName("the credit is recorded before payment-service is called, then marked done")
  void recordedThenCompleted() {
    credits.creditBack(user, new BigDecimal("150.00"), "INR", order);

    verify(jdbc).sql(startsWith("INSERT INTO orders.pending_wallet_credits"));
    ArgumentCaptor<PaymentFeignClient.WalletCredit> sent =
        ArgumentCaptor.forClass(PaymentFeignClient.WalletCredit.class);
    verify(payments).walletCredit(sent.capture());
    assertThat(sent.getValue().idempotencyKey()).isEqualTo("order-wallet-back-" + order);
    verify(jdbc).sql(contains("SET completed_at = now()"));
  }

  @Test
  @DisplayName("a payment-service failure leaves the credit due again instead of losing it")
  void failureIsRetriedLater() {
    when(payments.walletCredit(any())).thenThrow(new IllegalStateException("down"));

    credits.creditBack(user, new BigDecimal("150.00"), "INR", order);

    verify(jdbc).sql(contains("next_attempt_at = now() + make_interval"));
    verify(jdbc, never()).sql(contains("SET completed_at = now()"));
  }

  @Test
  @DisplayName(
      "an ambiguous debit failure asks payment-service to reverse it, never to credit blindly")
  @SuppressWarnings("unchecked")
  void reversalUsesReverseEndpoint() {
    when(jdbc.sql(startsWith("SELECT user_id"))
            .param(anyString(), any())
            .query(any(RowMapper.class))
            .optional())
        .thenReturn(
            Optional.of(
                new WalletCreditBacks.Pending(
                    user,
                    new BigDecimal("150.00"),
                    "INR",
                    order,
                    0,
                    "REVERSE_DEBIT",
                    "order-debit-" + order)));

    credits.reverseDebit(user, new BigDecimal("150.00"), "INR", order);

    ArgumentCaptor<PaymentFeignClient.WalletReverse> sent =
        ArgumentCaptor.forClass(PaymentFeignClient.WalletReverse.class);
    verify(payments).walletReverse(sent.capture());
    assertThat(sent.getValue().debitKey()).isEqualTo("order-debit-" + order);
    assertThat(sent.getValue().creditKey()).isEqualTo("order-wallet-back-" + order);
    verify(payments, never()).walletCredit(any());
  }
}
