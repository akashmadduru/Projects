package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.domain.HistoryActor;
import com.acme.ecommerce.order.domain.OrderStatus;
import com.acme.ecommerce.order.service.OrderStore.CancelClaim;
import com.acme.ecommerce.order.service.OrderStore.CancelCommit;
import com.acme.ecommerce.order.support.AbstractCancelIT;
import com.acme.ecommerce.order.support.CancelFixture.Built;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-14 A6: the claim, fail and commit transactions of OrderStore on the real schema. */
class OrderStoreCancelIT extends AbstractCancelIT {

  private BigDecimal amountOf(UUID shipmentId, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM orders.order_shipments WHERE id = ?", BigDecimal.class, shipmentId);
  }

  private int attemptOf(UUID shipmentId) {
    return jdbc.queryForObject(
        "SELECT cancel_attempt FROM orders.order_shipments WHERE id = ?", Integer.class, shipmentId);
  }

  @Test
  @DisplayName("a whole claim freezes the shares in creation order: 850, 510 and the remainder 390")
  void claimFreezesSharesInStableOrder() {
    Built b = workedExample();

    CancelClaim claim = store.claimCancel(null, null, b.orderId(), null);

    assertThat(claim.claims()).hasSize(3);
    assertThat(claim.claims()).extracting(OrderStore.Claimed::shipmentId).containsExactlyElementsOf(b.shipmentIds());
    List<UUID> ids = b.shipmentIds();
    assertThat(amountOf(ids.get(0), "cancel_refund_amount")).isEqualByComparingTo("850.00");
    assertThat(amountOf(ids.get(1), "cancel_refund_amount")).isEqualByComparingTo("510.00");
    assertThat(amountOf(ids.get(2), "cancel_refund_amount")).isEqualByComparingTo("390.00");
    assertThat(amountOf(ids.get(2), "cancel_shipping_refund")).isEqualByComparingTo("50.00");
    assertThat(amountOf(ids.get(0), "cancel_shipping_refund")).isEqualByComparingTo("0");
    assertThat(amountOf(ids.get(2), "cancel_wallet_leg")).isEqualByComparingTo("78.00");
    assertThat(amountOf(ids.get(2), "cancel_rest_leg")).isEqualByComparingTo("312.00");
    ids.forEach(id -> assertThat(cancelState(id)).isEqualTo("REQUESTED"));
    ids.forEach(id -> assertThat(shipmentStatus(id)).isEqualTo("PLACED")); // a claim ships nothing
  }

  @Test
  @DisplayName("claiming again reuses the frozen claim (same key, same amounts)")
  void claimIsIdempotent() {
    Built b = workedExample();
    CancelClaim first = store.claimCancel(null, null, b.orderId(), List.of(b.shipmentIds().get(0)));
    CancelClaim again = store.claimCancel(null, null, b.orderId(), List.of(b.shipmentIds().get(0)));
    assertThat(again).isEqualTo(first);
    assertThat(first.claims().get(0).key()).isEqualTo("ship-cancel-" + b.shipmentIds().get(0));
  }

  @Test
  @DisplayName("a FAILED claim starts the next key on the next claim; the amounts stay frozen")
  void failedClaimBumpsTheAttempt() {
    Built b = workedExample();
    UUID s1 = b.shipmentIds().get(0);
    store.claimCancel(null, null, b.orderId(), List.of(s1));
    store.failCancel(b.orderId(), s1, 0);
    assertThat(cancelState(s1)).isEqualTo("FAILED");

    CancelClaim retry = store.claimCancel(null, null, b.orderId(), List.of(s1));

    assertThat(retry.claims().get(0).key()).isEqualTo("ship-cancel-" + s1 + "-r1");
    assertThat(retry.claims().get(0).rest()).isEqualByComparingTo("680.00");
    assertThat(cancelState(s1)).isEqualTo("REQUESTED");
    assertThat(attemptOf(s1)).isEqualTo(1);
  }

  @Test
  @DisplayName("T2f of another attempt is a no-op")
  void failWithTheWrongAttemptChangesNothing() {
    Built b = workedExample();
    UUID s1 = b.shipmentIds().get(0);
    store.claimCancel(null, null, b.orderId(), List.of(s1));
    store.failCancel(b.orderId(), s1, 7);
    assertThat(cancelState(s1)).isEqualTo("REQUESTED");
  }

  @Test
  @DisplayName("T3 is idempotent, and a T3 of the wrong attempt is a no-op")
  void commitIsIdempotentAndAttemptMatched() {
    Built b = workedExample();
    UUID s1 = b.shipmentIds().get(0);
    store.claimCancel(null, null, b.orderId(), List.of(s1));

    CancelCommit wrong = store.commitCancel(b.orderId(), s1, 3, HistoryActor.ADMIN, "x");
    assertThat(wrong.changed()).isFalse();
    assertThat(cancelState(s1)).isEqualTo("REQUESTED");

    CancelCommit first = store.commitCancel(b.orderId(), s1, 0, HistoryActor.ADMIN, "x");
    CancelCommit second = store.commitCancel(b.orderId(), s1, 0, HistoryActor.ADMIN, "x");

    assertThat(first.changed()).isTrue();
    assertThat(first.credit()).isEqualByComparingTo("170.00");
    assertThat(first.walletKey()).isEqualTo("ship-cancel-wallet-" + s1);
    assertThat(second.changed()).isFalse();
    assertThat(shipmentStatus(s1)).isEqualTo("CANCELLED");
    assertThat(cancelState(s1)).isEqualTo("DONE");
    assertThat(refunded(b.orderId())).isEqualByComparingTo("850.00");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM orders.order_status_history WHERE shipment_id = ?"
                    + " AND status = 'CANCELLED'",
                Integer.class,
                s1))
        .isEqualTo(1);
    // the store never calls a remote service: no call can run inside its transactions
    verifyNoInteractions(payments);
  }

  @Test
  @DisplayName("T3 of the last live shipment cancels the order")
  void lastCommitCancelsTheOrder() {
    Built b = build(com.acme.ecommerce.order.domain.PaymentMethod.CARD, "0", "0", "0", "0", "100.00", "50.00");
    store.claimCancel(null, null, b.orderId(), null);
    CancelCommit one = store.commitCancel(b.orderId(), b.shipmentIds().get(0), 0, HistoryActor.USER, "x");
    assertThat(one.orderCancelled()).isFalse();
    CancelCommit two = store.commitCancel(b.orderId(), b.shipmentIds().get(1), 0, HistoryActor.USER, "x");
    assertThat(two.orderCancelled()).isTrue();
    assertThat(orderStatus(b.orderId())).isEqualTo(OrderStatus.CANCELLED.name());
    assertThat(paymentStatus(b.orderId())).isEqualTo("REFUNDED");
  }

  @Test
  @DisplayName("shipping clears a FAILED claim (the attempt stays), and a REQUESTED claim blocks it")
  void failedClaimIsClearedByShip() {
    Built b = workedExample();
    UUID s1 = b.shipmentIds().get(0);
    jdbc.update("UPDATE orders.order_shipments SET status = 'PACKED' WHERE id = ?", s1);
    store.claimCancel(null, null, b.orderId(), List.of(s1));

    assertThatThrownBy(
            () ->
                store.advanceShipment(
                    b.orderId(), null, s1, OrderStatus.SHIPPED, "n", "TRK", "dhl", HistoryActor.ADMIN))
        .isInstanceOfSatisfying(
            CodedException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

    store.failCancel(b.orderId(), s1, 0);
    store.claimCancel(null, null, b.orderId(), List.of(s1)); // attempt 1
    store.failCancel(b.orderId(), s1, 1);
    store.advanceShipment(b.orderId(), null, s1, OrderStatus.SHIPPED, "n", "TRK", "dhl", HistoryActor.ADMIN);

    assertThat(shipmentStatus(s1)).isEqualTo("SHIPPED");
    assertThat(cancelState(s1)).isNull();
    assertThat(amountOf(s1, "cancel_refund_amount")).isNull();
    assertThat(attemptOf(s1)).isEqualTo(1);
  }
}
