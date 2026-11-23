package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.client.PaymentFeignClient.RefundResult;
import com.acme.ecommerce.order.client.SellerInternalFeignClient.SellerRef;
import com.acme.ecommerce.order.domain.PaymentMethod;
import com.acme.ecommerce.order.event.OrderEventType;
import com.acme.ecommerce.order.support.AbstractCancelIT;
import com.acme.ecommerce.order.support.CancelFixture.Built;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** F-14 A7: the per-shipment cancel flow on the real schema; payment-service is mocked. */
class ShipmentCancelIT extends AbstractCancelIT {

  @Test
  @DisplayName(
      "AC-1: a seller cancels S1: its share is refunded, only S1 stock is released, one event")
  void sellerCancelsS1_refundsShare_onlyS1Stock_oneEvent() {
    Built b = workedExample();
    UUID s1 = b.shipmentIds().get(0);
    UUID s2 = b.shipmentIds().get(1);
    UUID sellerUser = UUID.randomUUID();
    when(sellers.byUser(sellerUser)).thenReturn(new SellerRef(b.sellerIds().get(0), "APPROVED"));

    lifecycle.sellerCancelShipment(sellerUser, s1, "out of stock");

    // R1 = 1000 * 1700/2000 = 850: wallet 170, card 680, under the shipment-scoped keys
    verify(payments)
        .refund(
            eq(b.orderId()),
            argThat(
                r ->
                    r.amount().compareTo(new BigDecimal("680.00")) == 0
                        && r.idempotencyKey().equals("ship-cancel-" + s1)
                        && r.destination() == null));
    assertThat(walletRowAmount(s1)).isEqualByComparingTo("170.00");
    assertThat(cancelState(s1)).isEqualTo("DONE");
    assertThat(shipmentStatus(s1)).isEqualTo("CANCELLED");
    assertThat(shipmentStatus(s2)).isEqualTo("PLACED");
    assertThat(orderStatus(b.orderId())).isEqualTo("PLACED");
    assertThat(paymentStatus(b.orderId())).isEqualTo("PARTIALLY_REFUNDED");
    assertThat(refunded(b.orderId())).isEqualByComparingTo("850.00");
    assertThat(releaseRows(b.orderId(), s1)).isEqualTo(1);
    assertThat(releaseRows(b.orderId(), s2)).isZero();
    verify(events, times(1))
        .publishShipment(eq(OrderEventType.SHIPMENT_STATUS_CHANGED), any(), any());
    verify(events, never()).publish(eq(OrderEventType.ORDER_CANCELLED), any());
  }

  @Test
  @DisplayName("AC-2: a replay after success makes no second refund, credit or event")
  void replayAfterSuccess_noSecondRefund_noEvent() {
    Built b = workedExample();
    cancel(b, 0);
    cancel(b, 0);

    verify(payments, times(1)).refund(eq(b.orderId()), any());
    assertThat(walletRows(b.orderId())).isEqualTo(1);
    assertThat(releaseRows(b.orderId(), b.shipmentIds().get(0))).isEqualTo(1);
    verify(events, times(1))
        .publishShipment(eq(OrderEventType.SHIPMENT_STATUS_CHANGED), any(), any());
    assertThat(refunded(b.orderId())).isEqualByComparingTo("850.00");
  }

  @Test
  @DisplayName("AC-2: two shipments of one order refund under two distinct keys")
  void twoShipments_distinctKeys() {
    Built b = workedExample();
    cancel(b, 0);
    cancel(b, 1);

    verify(payments)
        .refund(
            eq(b.orderId()),
            argThat(
                r ->
                    r.idempotencyKey().equals("ship-cancel-" + b.shipmentIds().get(0))
                        && r.amount().compareTo(new BigDecimal("680.00")) == 0));
    verify(payments)
        .refund(
            eq(b.orderId()),
            argThat(
                r ->
                    r.idempotencyKey().equals("ship-cancel-" + b.shipmentIds().get(1))
                        && r.amount().compareTo(new BigDecimal("408.00")) == 0));
    assertThat(refunded(b.orderId())).isEqualByComparingTo("1360.00");
  }

  @Test
  @DisplayName("AC-3: payment-service down gives 503 and nothing changes; the retry reuses the key")
  void paymentDown_503_unchanged_retrySameKey() {
    Built b = workedExample();
    UUID s1 = b.shipmentIds().get(0);
    when(payments.refund(any(), any())).thenThrow(down());

    assertThatThrownBy(() -> cancel(b, 0))
        .isInstanceOfSatisfying(
            CodedException.class, e -> assertThat(e.errorCode().status()).isEqualTo(503));

    assertThat(cancelState(s1)).isEqualTo("REQUESTED");
    assertThat(shipmentStatus(s1)).isEqualTo("PLACED");
    assertThat(refunded(b.orderId())).isEqualByComparingTo("0");
    assertThat(walletRows(b.orderId())).isZero();
    assertThat(releaseRows(b.orderId(), s1)).isZero();
    verify(events, never()).publishShipment(any(), any(), any());

    reset(payments);
    when(payments.refund(any(), any()))
        .thenReturn(new RefundResult("PARTIALLY_REFUNDED", BigDecimal.TEN, "SUCCEEDED"));
    cancel(b, 0);

    verify(payments)
        .refund(eq(b.orderId()), argThat(r -> r.idempotencyKey().equals("ship-cancel-" + s1)));
    assertThat(cancelState(s1)).isEqualTo("DONE");
  }

  @Test
  @DisplayName("AC-3: a FAILED card refund gives 503 with no change; the retry uses the -r1 key")
  void paymentFailed_503_retryUsesR1() {
    Built b = workedExample();
    UUID s1 = b.shipmentIds().get(0);
    when(payments.refund(any(), any()))
        .thenReturn(new RefundResult("PARTIALLY_REFUNDED", BigDecimal.TEN, "FAILED"));

    assertThatThrownBy(() -> cancel(b, 0))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));

    assertThat(cancelState(s1)).isEqualTo("FAILED");
    assertThat(shipmentStatus(s1)).isEqualTo("PLACED");
    assertThat(refunded(b.orderId())).isEqualByComparingTo("0");
    assertThat(walletRows(b.orderId())).isZero();
    assertThat(releaseRows(b.orderId(), s1)).isZero();

    when(payments.refund(any(), any()))
        .thenReturn(new RefundResult("PARTIALLY_REFUNDED", BigDecimal.TEN, "SUCCEEDED"));
    cancel(b, 0);

    verify(payments)
        .refund(
            eq(b.orderId()), argThat(r -> r.idempotencyKey().equals("ship-cancel-" + s1 + "-r1")));
    assertThat(cancelState(s1)).isEqualTo("DONE");
    assertThat(shipmentStatus(s1)).isEqualTo("CANCELLED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"SHIPPED", "OUT_FOR_DELIVERY", "DELIVERED", "PENDING_PAYMENT", "HOLD"})
  @DisplayName("AC-5: a shipment that cannot be cancelled is a 409 and no payment call is made")
  void notCancellable_409_noPaymentCall(String state) {
    Built b = workedExample();
    UUID s1 = b.shipmentIds().get(0);
    if ("HOLD".equals(state)) {
      jdbc.update("UPDATE orders.orders SET risk_hold = true WHERE id = ?", b.orderId());
    } else if ("PENDING_PAYMENT".equals(state)) {
      jdbc.update("UPDATE orders.orders SET status = 'PENDING_PAYMENT' WHERE id = ?", b.orderId());
      jdbc.update("UPDATE orders.order_shipments SET status = 'PENDING_PAYMENT' WHERE id = ?", s1);
    } else {
      jdbc.update("UPDATE orders.order_shipments SET status = ? WHERE id = ?", state, s1);
    }

    assertThatThrownBy(() -> cancel(b, 0))
        .isInstanceOfSatisfying(
            CodedException.class,
            e -> {
              assertThat(e.errorCode().status()).isEqualTo(409);
              assertThat(e.errorCode())
                  .isEqualTo(
                      List.of("SHIPPED", "OUT_FOR_DELIVERY", "DELIVERED").contains(state)
                          ? ErrorCode.ORDER_NOT_CANCELLABLE
                          : ErrorCode.INVALID_STATUS_TRANSITION);
            });

    verifyNoInteractions(payments);
    assertThat(cancelState(s1)).isNull();
    assertThat(releaseRows(b.orderId(), s1)).isZero();
  }

  @Test
  @DisplayName(
      "AC-11: an uncollected COD shipment is cancelled with no payment call and no refund row")
  void codUncollected_noPaymentCall() {
    Built b = build(PaymentMethod.COD, "0", "0", "0", "0", "100.00", "50.00");
    UUID s1 = b.shipmentIds().get(0);

    cancel(b, 0);

    verifyNoInteractions(payments);
    assertThat(cancelState(s1)).isEqualTo("DONE");
    assertThat(shipmentStatus(s1)).isEqualTo("CANCELLED");
    assertThat(refunded(b.orderId())).isEqualByComparingTo("0");
    assertThat(paymentStatus(b.orderId())).isEqualTo("COD_PENDING");
    assertThat(walletRows(b.orderId())).isZero();
    assertThat(releaseRows(b.orderId(), s1)).isEqualTo(1);
  }

  @Test
  @DisplayName("AC-11: a zero-amount cancel moves no money and records no refund")
  void zeroAmount_noRefundRow() {
    Built b = build(PaymentMethod.CARD, "150.00", "0", "0", "0", "100.00", "50.00");
    // the coupon covers the whole subtotal: every share is 0.00
    cancel(b, 0);

    verifyNoInteractions(payments);
    assertThat(shipmentStatus(b.shipmentIds().get(0))).isEqualTo("CANCELLED");
    assertThat(refunded(b.orderId())).isEqualByComparingTo("0");
    assertThat(paymentStatus(b.orderId())).isEqualTo("PAID");
    assertThat(walletRows(b.orderId())).isZero();
  }
}
