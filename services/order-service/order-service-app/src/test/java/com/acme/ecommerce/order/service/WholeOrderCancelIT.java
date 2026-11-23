package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.acme.ecommerce.order.client.PaymentFeignClient.RefundRequest;
import com.acme.ecommerce.order.domain.PaymentMethod;
import com.acme.ecommerce.order.event.OrderEventType;
import com.acme.ecommerce.order.support.AbstractCancelIT;
import com.acme.ecommerce.order.support.CancelFixture.Built;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** F-14 A9: a whole-order cancel is the cancel of every live shipment (AC-9). */
class WholeOrderCancelIT extends AbstractCancelIT {

  private List<RefundRequest> refundRequests(UUID orderId) {
    ArgumentCaptor<RefundRequest> captor = forClass(RefundRequest.class);
    verify(payments, atLeast(0)).refund(eq(orderId), captor.capture());
    return captor.getAllValues();
  }

  @Test
  @DisplayName(
      "AC-9: a whole cancel after a partial cancel refunds only the remainder (850/510/390)")
  void afterPartialCancel_refundsRemainder_workedExample() {
    Built b = workedExample();
    cancel(b, 0); // S1: 850 = wallet 170 + card 680

    lifecycle.cancelByUser(b.userId(), b.orderId());

    List<RefundRequest> sent = refundRequests(b.orderId());
    BigDecimal card =
        sent.stream().map(RefundRequest::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    // the card was refunded 1400 in total, never 1400 again on top of S1's 680
    assertThat(card).isEqualByComparingTo("1400.00");
    assertThat(sent).hasSize(3);
    assertThat(sent)
        .extracting(RefundRequest::idempotencyKey)
        .containsExactlyInAnyOrder(
            "ship-cancel-" + b.shipmentIds().get(0),
            "ship-cancel-" + b.shipmentIds().get(1),
            "ship-cancel-" + b.shipmentIds().get(2))
        .noneMatch(k -> k.equals("cancel-" + b.orderId()));
    assertThat(sent.get(1).amount()).isEqualByComparingTo("408.00");
    assertThat(sent.get(2).amount()).isEqualByComparingTo("312.00");
    assertThat(walletRowAmount(b.shipmentIds().get(0))).isEqualByComparingTo("170.00");
    assertThat(walletRowAmount(b.shipmentIds().get(1))).isEqualByComparingTo("102.00");
    assertThat(walletRowAmount(b.shipmentIds().get(2))).isEqualByComparingTo("78.00");
    assertThat(refunded(b.orderId())).isEqualByComparingTo("1750.00");
    assertThat(orderStatus(b.orderId())).isEqualTo("CANCELLED");
    assertThat(paymentStatus(b.orderId())).isEqualTo("REFUNDED");
  }

  @Test
  @DisplayName("AC-9: coupon, coins and the order event run once, at the transition to CANCELLED")
  void followUpsOnce() {
    Built b = workedExample();
    cancel(b, 0);
    assertThat(outboxRows(b.orderId(), "RELEASE_COINS")).isZero(); // not yet cancelled

    lifecycle.cancelByUser(b.userId(), b.orderId());
    lifecycle.cancelByUser(b.userId(), b.orderId()); // a replay of the whole cancel

    assertThat(outboxRows(b.orderId(), "RELEASE_COINS")).isEqualTo(1);
    verify(events, times(1)).publish(eq(OrderEventType.ORDER_CANCELLED), any());
    // the wallet is returned by the per-shipment rows, never by the order-wide credit-back
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM orders.pending_wallet_credits WHERE idem_key = ?",
                Integer.class,
                "order-wallet-back-" + b.orderId()))
        .isZero();
    assertThat(walletRows(b.orderId())).isEqualTo(3);
    verify(events, times(3))
        .publishShipment(eq(OrderEventType.SHIPMENT_STATUS_CHANGED), any(), any());
  }

  @Test
  @DisplayName(
      "AC-9: shares of 3+ shipments with a coupon and shipping sum to the total to the cent")
  void rounding3Shipments_sumsToTotal() {
    Built b = build(PaymentMethod.CARD, "10.00", "0", "7.00", "0", "33.33", "33.33", "33.34");

    lifecycle.cancelByUser(b.userId(), b.orderId());

    BigDecimal card =
        refundRequests(b.orderId()).stream()
            .map(RefundRequest::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(b.total()).isEqualByComparingTo("97.00");
    assertThat(card).isEqualByComparingTo(b.total());
    assertThat(refunded(b.orderId())).isEqualByComparingTo(b.total());
    assertThat(paymentStatus(b.orderId())).isEqualTo("REFUNDED");
  }
}
