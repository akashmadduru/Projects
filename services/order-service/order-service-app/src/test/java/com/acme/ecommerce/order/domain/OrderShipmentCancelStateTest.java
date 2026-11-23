package com.acme.ecommerce.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** F-14 A2: a new shipment has no cancel claim and a zero cancel shipping share. */
class OrderShipmentCancelStateTest {

  private OrderShipment shipment() {
    return OrderShipment.of(
        null,
        UUID.randomUUID(),
        "S",
        null,
        "WH1",
        "560001",
        OrderStatus.PLACED,
        null,
        Instant.now());
  }

  @Test
  void newShipmentHoldsNoClaimAndIsNotCancelled() {
    OrderShipment s = shipment();
    assertThat(s.holdsCancelClaim()).isFalse();
    assertThat(s.isCancelled()).isFalse();
    assertThat(s.getCancelState()).isNull();
    assertThat(s.getCancelAttempt()).isZero();
    assertThat(s.getCancelShippingRefund()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void cancelledShipmentReportsCancelled() {
    OrderShipment s = shipment();
    s.cancel(Instant.now());
    assertThat(s.isCancelled()).isTrue();
  }
}
