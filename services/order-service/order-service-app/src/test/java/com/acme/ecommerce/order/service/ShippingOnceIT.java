package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.order.domain.Order.ReturnLine;
import com.acme.ecommerce.order.domain.PaymentMethod;
import com.acme.ecommerce.order.domain.ReturnReason;
import com.acme.ecommerce.order.support.AbstractCancelIT;
import com.acme.ecommerce.order.support.CancelFixture.Built;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-14 B1 (AC-7): shipping is refunded once across returns and shipment cancels. */
class ShippingOnceIT extends AbstractCancelIT {

  private void deliver(UUID shipmentId) {
    jdbc.update(
        "UPDATE orders.order_shipments SET status = 'DELIVERED', delivered_at = now()"
            + " WHERE id = ?",
        shipmentId);
  }

  private void returnShipment(Built b, UUID shipmentId) {
    UUID product =
        jdbc.queryForObject(
            "SELECT product_id FROM orders.order_items WHERE shipment_id = ?",
            UUID.class,
            shipmentId);
    store.requestReturn(
        b.userId(),
        b.orderId(),
        List.of(new ReturnLine(product, null, 1)),
        ReturnReason.DAMAGED,
        null,
        "SOURCE",
        shipmentId);
  }

  private BigDecimal returnShipping(UUID orderId) {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(shipping_refund), 0) FROM orders.order_returns WHERE order_id = ?",
        BigDecimal.class,
        orderId);
  }

  private BigDecimal cancelShipping(UUID orderId) {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(cancel_shipping_refund), 0) FROM orders.order_shipments"
            + " WHERE order_id = ?",
        BigDecimal.class,
        orderId);
  }

  @Test
  @DisplayName("AC-7: S1 and S2 cancelled, then S3 returned: the return carries the shipping once")
  void cancelThenReturn_returnCarriesShipping() {
    Built b = workedExample();
    cancel(b, 0);
    cancel(b, 1);
    deliver(b.shipmentIds().get(2));

    returnShipment(b, b.shipmentIds().get(2));

    assertThat(cancelShipping(b.orderId())).isEqualByComparingTo("0");
    assertThat(returnShipping(b.orderId())).isEqualByComparingTo("50.00");
  }

  @Test
  @DisplayName("AC-7: S1 returned, then S2 and S3 cancelled: the last cancel carries the shipping")
  void returnThenCancel_cancelCarriesShipping() {
    Built b = workedExample();
    deliver(b.shipmentIds().get(0));
    returnShipment(b, b.shipmentIds().get(0));
    assertThat(returnShipping(b.orderId())).isEqualByComparingTo("0");

    cancel(b, 1);
    cancel(b, 2);

    assertThat(returnShipping(b.orderId())).isEqualByComparingTo("0");
    assertThat(cancelShipping(b.orderId())).isEqualByComparingTo("50.00");
  }

  @Test
  @DisplayName("AC-7: a single-shipment order returns shipping exactly as before")
  void singleShipment_unchanged() {
    Built b = build(PaymentMethod.CARD, "0", "0", "50.00", "0", "100.00");
    deliver(b.shipmentIds().get(0));

    returnShipment(b, b.shipmentIds().get(0));

    assertThat(returnShipping(b.orderId())).isEqualByComparingTo("50.00");
    assertThat(cancelShipping(b.orderId())).isEqualByComparingTo("0");
  }
}
