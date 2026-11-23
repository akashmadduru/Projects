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
import com.acme.ecommerce.order.client.PaymentFeignClient.RefundResult;
import com.acme.ecommerce.order.domain.PaymentMethod;
import com.acme.ecommerce.order.support.AbstractCancelIT;
import com.acme.ecommerce.order.support.CancelFixture.Built;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-14 A7 (CTO condition 2): the wallet leg is durable; only the card leg is strict. */
class ShipmentCancelWalletIT extends AbstractCancelIT {

  private boolean completed(UUID shipmentId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT completed_at IS NOT NULL FROM orders.pending_wallet_credits WHERE idem_key = ?",
            Boolean.class,
            "ship-cancel-wallet-" + shipmentId));
  }

  @Test
  @DisplayName("a wallet-only cancel with payment-service down commits and queues the credit")
  void walletOnlyCancel_paymentDown_commitsAndQueuesCredit() {
    Built b = build(PaymentMethod.WALLET, "0", "0", "0", "150.00", "100.00", "50.00");
    UUID s1 = b.shipmentIds().get(0);
    when(payments.refund(any(), any())).thenThrow(down());
    doThrow(down()).when(payments).walletCredit(any());

    cancel(b, 0); // no exception: nothing synchronous depends on payment-service

    verify(payments, never()).refund(any(), any());
    assertThat(cancelState(s1)).isEqualTo("DONE");
    assertThat(shipmentStatus(s1)).isEqualTo("CANCELLED");
    assertThat(releaseRows(b.orderId(), s1)).isEqualTo(1);
    assertThat(refunded(b.orderId())).isEqualByComparingTo("100.00");
    assertThat(walletRowAmount(s1)).isEqualByComparingTo("100.00");
    assertThat(completed(s1)).isFalse();

    // payment-service comes back: the retry job delivers the queued credit
    reset(payments);
    jdbc.update(
        "UPDATE orders.pending_wallet_credits SET next_attempt_at = now() - interval '1 minute'"
            + " WHERE idem_key = ?",
        "ship-cancel-wallet-" + s1);
    walletCredits.retryDueLocked();
    assertThat(completed(s1)).isTrue();
    verify(payments)
        .walletCredit(argThat(c -> c.idempotencyKey().equals("ship-cancel-wallet-" + s1)));
  }

  @Test
  @DisplayName("a card failure on a card-plus-wallet order gives 503 and queues no credit")
  void cardFails_onCardPlusWallet_503_noCreditQueued() {
    Built b = build(PaymentMethod.CARD, "0", "0", "0", "40.00", "100.00", "50.00");
    UUID s1 = b.shipmentIds().get(0);
    when(payments.refund(any(), any()))
        .thenReturn(new RefundResult("PARTIALLY_REFUNDED", BigDecimal.TEN, "FAILED"));

    assertThatThrownBy(() -> cancel(b, 0))
        .isInstanceOfSatisfying(
            CodedException.class, e -> assertThat(e.errorCode().status()).isEqualTo(503));

    assertThat(walletRows(b.orderId())).isZero();
    verify(payments, never()).walletCredit(any());
    assertThat(cancelState(s1)).isEqualTo("FAILED");
    assertThat(shipmentStatus(s1)).isEqualTo("PLACED");
    assertThat(refunded(b.orderId())).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("a replay queues no duplicate credit and sends it once")
  void replay_queuesNoDuplicateCredit() {
    Built b = build(PaymentMethod.CARD, "0", "0", "0", "40.00", "100.00", "50.00");
    UUID s1 = b.shipmentIds().get(0);

    cancel(b, 0);
    cancel(b, 0);

    // wallet share of 100 on a 150 order with 40 from the wallet: round2(100 * 40 / 150) = 26.67
    assertThat(walletRows(b.orderId())).isEqualTo(1);
    assertThat(walletRowAmount(s1)).isEqualByComparingTo("26.67");
    assertThat(completed(s1)).isTrue();
    verify(payments, times(1))
        .walletCredit(argThat(c -> c.idempotencyKey().equals("ship-cancel-wallet-" + s1)));
  }
}
