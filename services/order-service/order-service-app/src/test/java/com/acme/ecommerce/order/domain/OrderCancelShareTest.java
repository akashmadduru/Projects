package com.acme.ecommerce.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.order.domain.Order.Allocation;
import com.acme.ecommerce.order.domain.Order.CancelShare;
import com.acme.ecommerce.order.domain.Order.ShipTo;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** F-14 A3: the pro-rata cancel share, the remainder rule and the shipping-once rule. */
class OrderCancelShareTest {

  private static final Instant NOW = Instant.parse("2026-11-23T00:00:00Z");

  /** One single-line shipment per amount; coupon and coins reduce the paid items value. */
  private Order order(String discount, String coins, String shipping, String... amounts) {
    Order o =
        Order.draft(
            UUID.randomUUID(),
            "a@b.c",
            UUID.randomUUID().toString(),
            UUID.randomUUID(),
            new ShipTo("N", "1", "l1", null, "c", "s", "560001", "IN"),
            "INR",
            PaymentMethod.CARD,
            NOW);
    List<Allocation> allocations = new ArrayList<>();
    BigDecimal sub = BigDecimal.ZERO;
    for (int i = 0; i < amounts.length; i++) {
      UUID p = UUID.randomUUID();
      o.addItem(p, null, null, "sku" + i, "P" + i, new BigDecimal(amounts[i]), 1, null);
      allocations.add(new Allocation(p, null, null, UUID.randomUUID(), "WH" + i, "560001", 1));
      sub = sub.add(new BigDecimal(amounts[i]));
    }
    BigDecimal disc = new BigDecimal(discount);
    BigDecimal coin = new BigDecimal(coins);
    BigDecimal ship = new BigDecimal(shipping);
    o.applyMoney(0, coin, BigDecimal.ZERO, false, null);
    o.begin(
        new Pricing(
            sub,
            disc,
            ship,
            sub.subtract(disc).subtract(coin).add(ship),
            BigDecimal.ZERO,
            null,
            null,
            null,
            null,
            coin,
            false),
        null,
        null,
        NOW);
    o.assignShipments(allocations, Map.of(), pin -> null, NOW);
    o.getShipments().forEach(s -> ReflectionTestUtils.setField(s, "id", UUID.randomUUID()));
    return o;
  }

  /** Marks a shipment as holding a frozen cancel claim (what the store does at the claim). */
  private void claim(OrderShipment s, CancelShare share) {
    ReflectionTestUtils.setField(s, "cancelState", "REQUESTED");
    ReflectionTestUtils.setField(s, "cancelRefundAmount", share.total());
    ReflectionTestUtils.setField(s, "cancelShippingRefund", share.shipping());
  }

  private OrderShipment byAmount(Order o, String amount) {
    return o.getShipments().stream()
        .filter(s -> s.getAmount().compareTo(new BigDecimal(amount)) == 0)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void workedExample_850_510_390() {
    Order o = order("200.00", "100.00", "50.00", "1000.00", "600.00", "400.00");
    OrderShipment s1 = byAmount(o, "1000.00");
    OrderShipment s2 = byAmount(o, "600.00");
    OrderShipment s3 = byAmount(o, "400.00");

    CancelShare c1 = o.cancelShare(s1);
    assertThat(c1.items()).isEqualByComparingTo("850.00");
    assertThat(c1.shipping()).isEqualByComparingTo("0");
    claim(s1, c1);
    CancelShare c2 = o.cancelShare(s2);
    assertThat(c2.total()).isEqualByComparingTo("510.00");
    claim(s2, c2);
    CancelShare c3 = o.cancelShare(s3);
    assertThat(c3.items()).isEqualByComparingTo("340.00");
    assertThat(c3.shipping()).isEqualByComparingTo("50.00");
    assertThat(c3.total()).isEqualByComparingTo("390.00");
    assertThat(c1.total().add(c2.total()).add(c3.total())).isEqualByComparingTo("1750.00");
  }

  @Test
  void threeOrMoreShipments_sumToTotalToTheCent() {
    // 3 equal lines with a coupon that does not divide evenly: the last share takes the remainder.
    Order o = order("10.00", "0", "7.00", "33.33", "33.33", "33.34", "50.00");
    BigDecimal sum = BigDecimal.ZERO;
    for (OrderShipment s : o.getShipments()) {
      CancelShare c = o.cancelShare(s);
      claim(s, c);
      sum = sum.add(c.total());
    }
    assertThat(sum).isEqualByComparingTo(o.getTotalAmount());
  }

  @Test
  void returnAndCancelMix_cancelTakesTheRemainder() {
    Order o = order("0", "0", "40.00", "100.00", "50.00");
    OrderShipment a = byAmount(o, "100.00");
    OrderShipment b = byAmount(o, "50.00");
    // shipment a was delivered and fully returned (refund 100, no shipping): its lines are covered
    OrderItem item =
        o.getItems().stream()
            .filter(i -> a.getId().equals(i.getShipmentId()))
            .findFirst()
            .orElseThrow();
    OrderReturn r =
        OrderReturn.request(
            o,
            ReturnReason.DAMAGED,
            null,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            "SOURCE",
            a.getId(),
            NOW);
    r.addItem(item.getProductId(), item.getVariantId(), "P0", 1, item.getUnitPrice());
    o.getReturns().add(r);

    CancelShare c = o.cancelShare(b);
    assertThat(c.items()).isEqualByComparingTo("50.00");
    assertThat(c.shipping()).isEqualByComparingTo("40.00");
  }

  @Test
  void noShippingWhenAClaimAlreadyCarriesIt() {
    Order o = order("0", "0", "40.00", "100.00", "50.00");
    OrderShipment a = byAmount(o, "100.00");
    OrderShipment b = byAmount(o, "50.00");
    ReflectionTestUtils.setField(a, "cancelState", "DONE");
    ReflectionTestUtils.setField(a, "cancelRefundAmount", new BigDecimal("140.00"));
    ReflectionTestUtils.setField(a, "cancelShippingRefund", new BigDecimal("40.00"));
    CancelShare c = o.cancelShare(b);
    assertThat(c.shipping()).isEqualByComparingTo("0");
    assertThat(c.items()).isEqualByComparingTo("50.00");
  }

  @Test
  void singleShipmentCompletesAndCarriesShipping() {
    Order o = order("0", "0", "40.00", "100.00");
    CancelShare c = o.cancelShare(o.getShipments().get(0));
    assertThat(c.total()).isEqualByComparingTo("140.00");
  }
}
