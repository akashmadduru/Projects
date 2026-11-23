package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.api.dto.AdminRequests.StatusUpdate;
import com.acme.ecommerce.order.api.dto.AdminRequests.TargetStatus;
import com.acme.ecommerce.order.client.PaymentFeignClient.RefundResult;
import com.acme.ecommerce.order.event.OrderEventType;
import com.acme.ecommerce.order.support.AbstractCancelIT;
import com.acme.ecommerce.order.support.CancelFixture.Built;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-14 A10 (AC-10): the order row lock makes a cancel and a ship of one shipment exclusive. */
class ShipmentCancelRaceIT extends AbstractCancelIT {

  private StatusUpdate ship() {
    return new StatusUpdate(TargetStatus.SHIPPED, "shipped", "TRK-1", "dhl");
  }

  private static Throwable failure(Future<?> f) throws InterruptedException {
    try {
      f.get(30, TimeUnit.SECONDS);
      return null;
    } catch (java.util.concurrent.ExecutionException e) {
      return e.getCause();
    } catch (java.util.concurrent.TimeoutException e) {
      throw new AssertionError("timed out", e);
    }
  }

  private void packed(Built b) {
    jdbc.update(
        "UPDATE orders.order_shipments SET status = 'PACKED' WHERE id = ?", b.shipmentIds().get(0));
  }

  @Test
  @DisplayName("AC-10: a cancel racing a ship has exactly one winner and the state matches it")
  void cancelVsShip_exactlyOneWins() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 6; round++) {
        Built b = workedExample();
        UUID s1 = b.shipmentIds().get(0);
        packed(b);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Object> cancel =
            () -> {
              go.await();
              return lifecycle.adminCancelShipment(b.orderId(), s1, "race");
            };
        Callable<Object> shipIt =
            () -> {
              go.await();
              return lifecycle.adminAdvanceShipment(b.orderId(), s1, ship());
            };
        Future<Object> c = pool.submit(cancel);
        Future<Object> s = pool.submit(shipIt);
        go.countDown();
        Throwable cancelFailure = failure(c);
        Throwable shipFailure = failure(s);

        assertThat((cancelFailure == null ? 1 : 0) + (shipFailure == null ? 1 : 0))
            .as("exactly one of cancel and ship wins (round %s)", round)
            .isEqualTo(1);
        if (cancelFailure == null) {
          assertThat(shipFailure).isInstanceOf(CodedException.class);
          assertThat(shipmentStatus(s1)).isEqualTo("CANCELLED");
          assertThat(cancelState(s1)).isEqualTo("DONE");
        } else {
          assertThat(cancelFailure).isInstanceOf(CodedException.class);
          assertThat(((CodedException) cancelFailure).errorCode())
              .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
          assertThat(shipmentStatus(s1)).isEqualTo("SHIPPED");
          assertThat(cancelState(s1)).isNull();
          assertThat(refunded(b.orderId())).isEqualByComparingTo("0");
          assertThat(releaseRows(b.orderId(), s1)).isZero();
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "AC-10: two concurrent cancels of one shipment make one refund, one credit, one event")
  void doubleCancel_oneRefund() throws Exception {
    Built b = workedExample();
    UUID s1 = b.shipmentIds().get(0);
    CountDownLatch bothIn = new CountDownLatch(2);
    when(payments.refund(any(), any()))
        .thenAnswer(
            i -> {
              bothIn.countDown();
              bothIn.await(5, TimeUnit.SECONDS); // both callers reach the card leg
              return new RefundResult("PARTIALLY_REFUNDED", BigDecimal.TEN, "SUCCEEDED");
            });
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> a = pool.submit(() -> lifecycle.adminCancelShipment(b.orderId(), s1, "one"));
      Future<?> c = pool.submit(() -> lifecycle.adminCancelShipment(b.orderId(), s1, "two"));
      assertThat(failure(a)).isNull();
      assertThat(failure(c)).isNull();
    } finally {
      pool.shutdownNow();
    }

    // payment-service dedups by key: both calls carried the one key
    assertThat(
            mockingDetails(payments).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("refund"))
                .map(
                    inv ->
                        ((com.acme.ecommerce.order.client.PaymentFeignClient.RefundRequest)
                                inv.getArgument(1))
                            .idempotencyKey())
                .distinct())
        .containsExactly("ship-cancel-" + s1);
    assertThat(refunded(b.orderId())).isEqualByComparingTo("850.00");
    assertThat(walletRows(b.orderId())).isEqualTo(1);
    assertThat(releaseRows(b.orderId(), s1)).isEqualTo(1);
    verify(events, times(1))
        .publishShipment(eq(OrderEventType.SHIPMENT_STATUS_CHANGED), any(), any());
  }

  @Test
  @DisplayName("AC-10: shipping a shipment whose cancel is in progress is a 409")
  void shipWhileRequested_409() throws Exception {
    Built b = workedExample();
    UUID s1 = b.shipmentIds().get(0);
    packed(b);
    CountDownLatch inCall = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(payments.refund(any(), any()))
        .thenAnswer(
            i -> {
              inCall.countDown();
              release.await(10, TimeUnit.SECONDS);
              return new RefundResult("PARTIALLY_REFUNDED", BigDecimal.TEN, "SUCCEEDED");
            });
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<?> cancelling = pool.submit(() -> lifecycle.adminCancelShipment(b.orderId(), s1, "x"));
      assertThat(inCall.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(cancelState(s1)).isEqualTo("REQUESTED");

      assertThatThrownBy(() -> lifecycle.adminAdvanceShipment(b.orderId(), s1, ship()))
          .isInstanceOfSatisfying(
              CodedException.class,
              e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
      assertThat(shipmentStatus(s1)).isEqualTo("PACKED");

      release.countDown();
      assertThat(failure(cancelling)).isNull();
    } finally {
      pool.shutdownNow();
    }
    assertThat(shipmentStatus(s1)).isEqualTo("CANCELLED");
  }

  @Test
  @DisplayName("AC-10: cancelling a cancelled shipment is a 200 with no second effect")
  void cancelCancelled_200() {
    Built b = workedExample();
    cancel(b, 0);
    lifecycle.adminCancelShipment(b.orderId(), b.shipmentIds().get(0), "again");
    verify(payments, times(1)).refund(eq(b.orderId()), any());
    assertThat(refunded(b.orderId())).isEqualByComparingTo("850.00");
  }
}
