package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.order.client.PaymentFeignClient;
import com.acme.ecommerce.order.domain.OrderShipment;
import com.acme.ecommerce.order.domain.PaymentMethod;
import com.acme.ecommerce.order.repository.OrderRepository;
import com.acme.ecommerce.order.repository.OrderShipmentRepository;
import com.acme.ecommerce.order.support.AbstractOrderIntegrationTest;
import com.acme.ecommerce.order.support.CancelFixture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/** F-14 A2: V14 applies and the new columns are mapped on the entity. */
class ShipmentCancelMigrationIT extends AbstractOrderIntegrationTest {

  @MockitoBean private PaymentFeignClient payments;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private OrderRepository orders;
  @Autowired private OrderShipmentRepository shipments;
  @Autowired private TransactionTemplate tx;

  @Test
  void v14AddsTheCancelColumns() {
    List<String> cols =
        jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_schema = 'orders'"
                + " AND table_name = 'order_shipments' AND column_name LIKE 'cancel_%'",
            String.class);
    assertThat(cols)
        .containsExactlyInAnyOrder(
            "cancel_refund_amount",
            "cancel_shipping_refund",
            "cancel_wallet_leg",
            "cancel_rest_leg",
            "cancel_rest_route",
            "cancel_state",
            "cancel_attempt");
  }

  @Test
  void columnsAreMappedOnTheEntity() {
    var built = CancelFixture.build(orders, jdbc, PaymentMethod.CARD, "0", "0", "0", "0", "100.00");
    UUID sid = built.shipmentIds().get(0);
    jdbc.update(
        "UPDATE orders.order_shipments SET cancel_state = 'REQUESTED', cancel_attempt = 2,"
            + " cancel_rest_route = 'PAYMENT_SOURCE' WHERE id = ?",
        sid);
    OrderShipment s = tx.execute(x -> shipments.findById(sid).orElseThrow());
    assertThat(s.holdsCancelClaim()).isTrue();
    assertThat(s.getCancelAttempt()).isEqualTo(2);
    assertThat(s.getCancelRestRoute()).isEqualTo("PAYMENT_SOURCE");
  }
}
