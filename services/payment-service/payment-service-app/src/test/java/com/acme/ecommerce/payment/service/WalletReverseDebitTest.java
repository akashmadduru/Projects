package com.acme.ecommerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.payment.client.NotificationFeignClient;
import com.acme.ecommerce.payment.repository.WalletRepository;
import com.acme.ecommerce.payment.repository.WalletRepository.Debit;
import com.acme.ecommerce.payment.repository.WalletRepository.Wallet;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** TD-P8-23: a debit is given back only if it happened, and only once. */
class WalletReverseDebitTest {

  private final UUID user = UUID.randomUUID();
  private WalletRepository wallets;
  private WalletService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    wallets = mock(WalletRepository.class);
    TransactionTemplate tx = mock(TransactionTemplate.class);
    when(tx.execute(any()))
        .thenAnswer(
            inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
    service = new WalletService(wallets, tx, mock(NotificationFeignClient.class));
    when(wallets.lock(user))
        .thenReturn(Optional.of(new Wallet(user, new BigDecimal("50.00"), "INR")));
  }

  @Test
  @DisplayName("a debit that happened is credited back with its exact amount")
  void reversesExistingDebit() {
    when(wallets.findDebit("order-debit-1"))
        .thenReturn(Optional.of(new Debit(user, new BigDecimal("150.00"), "INR", "order-1")));

    assertThat(service.reverseDebit(user, "order-debit-1", "order-wallet-back-1")).isTrue();

    verify(wallets).update(user, new BigDecimal("200.00"), "INR");
    verify(wallets)
        .insertTxn(
            user,
            "CREDIT",
            new BigDecimal("150.00"),
            "INR",
            "REFUND",
            "order-1",
            "order-wallet-back-1",
            new BigDecimal("200.00"));
  }

  @Test
  @DisplayName("no debit means nothing to give back: no money is created")
  void noDebitNoCredit() {
    when(wallets.findDebit("order-debit-1")).thenReturn(Optional.empty());

    assertThat(service.reverseDebit(user, "order-debit-1", "order-wallet-back-1")).isFalse();

    verify(wallets, never())
        .insertTxn(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), any());
  }

  @Test
  @DisplayName(
      "a credit already made under the same key (refund or earlier reversal) blocks a second one")
  void alreadyReturned() {
    when(wallets.txnExists("order-wallet-back-1")).thenReturn(true);
    when(wallets.findDebit("order-debit-1"))
        .thenReturn(Optional.of(new Debit(user, new BigDecimal("150.00"), "INR", "order-1")));

    assertThat(service.reverseDebit(user, "order-debit-1", "order-wallet-back-1")).isFalse();

    verify(wallets, never()).update(any(), any(), anyString());
  }

  @Test
  @DisplayName("another user's debit is never returned to this user")
  void foreignDebit() {
    when(wallets.findDebit("order-debit-1"))
        .thenReturn(
            Optional.of(new Debit(UUID.randomUUID(), new BigDecimal("150.00"), "INR", "order-1")));

    assertThat(service.reverseDebit(user, "order-debit-1", "order-wallet-back-1")).isFalse();
  }
}
