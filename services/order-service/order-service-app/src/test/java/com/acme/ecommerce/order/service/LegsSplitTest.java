package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.order.domain.Order;
import com.acme.ecommerce.order.domain.Order.ShipTo;
import com.acme.ecommerce.order.domain.PaymentMethod;
import com.acme.ecommerce.order.domain.Pricing;
import com.acme.ecommerce.order.service.OrderStore.Legs;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** F-14 A4: the extracted ADR 0008 split and route (same maths as the return freeze). */
class LegsSplitTest {

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  private static Order order(PaymentMethod method, boolean paid, boolean failed) {
    Instant now = Instant.now();
    Order o =
        Order.draft(
            UUID.randomUUID(),
            "a@b.c",
            UUID.randomUUID().toString(),
            UUID.randomUUID(),
            new ShipTo("N", "1", "l1", null, "c", "s", "560001", "IN"),
            "INR",
            method,
            now);
    o.addItem(UUID.randomUUID(), null, null, "s", "P", d("100.00"), 1, null);
    o.begin(
        new Pricing(
            d("100.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            d("100.00"),
            BigDecimal.ZERO,
            null,
            null,
            null,
            null,
            BigDecimal.ZERO,
            false),
        null,
        null,
        now);
    if (paid) {
      o.markPaid("pay-1", now);
    }
    if (failed) {
      o.markPaymentFailed(now);
    }
    return o;
  }

  @Test
  void proRataWalletShare() {
    // R=850 of T=1750 with W=350: wallet round2(850*350/1750)=170, card 680
    Legs l = OrderStore.split(d("850.00"), d("350.00"), d("1750.00"), d("0"), d("0"));
    assertThat(l.wallet()).isEqualByComparingTo("170.00");
    assertThat(l.rest()).isEqualByComparingTo("680.00");
  }

  @Test
  void walletIsCappedBySiblingWalletAndLegsAlwaysSumToTheRefund() {
    // 390 of 1750: pro-rata wallet 78.00, siblings took 272 so only 78 of 350 is left; card 312
    Legs l = OrderStore.split(d("390.00"), d("350.00"), d("1750.00"), d("272.00"), d("1088.00"));
    assertThat(l.wallet()).isEqualByComparingTo("78.00");
    assertThat(l.rest()).isEqualByComparingTo("312.00");
    assertThat(l.wallet().add(l.rest())).isEqualByComparingTo("390.00");
  }

  @Test
  void excessOverTheCardRoomMovesToTheWallet() {
    // card room = 1000 - 200 - 790 = 10, so of R=100 (wallet share 20) the rest is capped at 10
    Legs l = OrderStore.split(d("100.00"), d("200.00"), d("1000.00"), d("0"), d("790.00"));
    assertThat(l.rest()).isEqualByComparingTo("10.00");
    assertThat(l.wallet()).isEqualByComparingTo("90.00");
  }

  @Test
  void noWalletMeansAllRest() {
    Legs l = OrderStore.split(d("42.50"), d("0"), d("100.00"), d("0"), d("0"));
    assertThat(l.wallet()).isEqualByComparingTo("0");
    assertThat(l.rest()).isEqualByComparingTo("42.50");
  }

  @Test
  void routeNoneWhenNothingWasPaid() {
    assertThat(OrderStore.route(order(PaymentMethod.COD, false, false), "SOURCE", d("10")))
        .isEqualTo("NONE");
    assertThat(OrderStore.route(order(PaymentMethod.CARD, false, false), "SOURCE", d("10")))
        .isEqualTo("NONE");
    assertThat(OrderStore.route(order(PaymentMethod.CARD, false, true), "SOURCE", d("10")))
        .isEqualTo("NONE");
  }

  @Test
  void routeNoneForZeroRestAndSourceForPaidCard() {
    Order paid = order(PaymentMethod.CARD, true, false);
    assertThat(OrderStore.route(paid, "SOURCE", BigDecimal.ZERO)).isEqualTo("NONE");
    assertThat(OrderStore.route(paid, "SOURCE", d("10"))).isEqualTo("PAYMENT_SOURCE");
    assertThat(OrderStore.route(paid, "WALLET", d("10"))).isEqualTo("PAYMENT_WALLET");
  }
}
