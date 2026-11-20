package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.order.config.OrderProperties;
import com.acme.ecommerce.order.domain.Order;
import com.acme.ecommerce.order.domain.Order.ReturnLine;
import com.acme.ecommerce.order.domain.Order.ShipTo;
import com.acme.ecommerce.order.domain.OrderReturn;
import com.acme.ecommerce.order.domain.OrderStatus;
import com.acme.ecommerce.order.domain.PaymentMethod;
import com.acme.ecommerce.order.domain.PaymentStatus;
import com.acme.ecommerce.order.domain.Pricing;
import com.acme.ecommerce.order.domain.ReturnReason;
import com.acme.ecommerce.order.repository.OrderRepository;
import com.acme.ecommerce.order.repository.OrderReturnRepository;
import com.acme.ecommerce.order.repository.OrderShipmentRepository;
import com.acme.ecommerce.order.service.OrderStore.ReturnRefundPlan;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** O-05a AC-10: the frozen wallet and rest legs add up to the refund to the cent, and the route. */
class FreezeLegsTest {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private static final UUID P = UUID.randomUUID();

  private final OrderReturnRepository returnsRepo = mock(OrderReturnRepository.class);
  private final OrderRepository orderRepo = mock(OrderRepository.class);
  private final OrderStore store =
      new OrderStore(
          orderRepo,
          returnsRepo,
          mock(OrderShipmentRepository.class),
          mock(OrderMapper.class),
          new OrderProperties());

  private static Order order(
      PaymentMethod method,
      int qty,
      String price,
      String discount,
      String coins,
      String shipping,
      String wallet) {
    Order o =
        Order.draft(
            UUID.randomUUID(),
            "a@b.c",
            "k",
            UUID.randomUUID(),
            new ShipTo("N", "1", "l1", null, "c", "s", "560001", "IN"),
            "INR",
            method,
            NOW);
    BigDecimal unit = new BigDecimal(price);
    o.addItem(P, null, null, "sku", "P", unit, qty, null);
    BigDecimal sub = unit.multiply(BigDecimal.valueOf(qty));
    BigDecimal disc = new BigDecimal(discount);
    BigDecimal coinValue = new BigDecimal(coins);
    BigDecimal ship = new BigDecimal(shipping);
    o.applyMoney(coinValue.intValue(), coinValue, new BigDecimal(wallet), false, null);
    o.begin(
        new Pricing(
            sub,
            disc,
            ship,
            sub.subtract(disc).subtract(coinValue).add(ship),
            BigDecimal.ZERO,
            null,
            null,
            null,
            null,
            coinValue,
            false),
        null,
        null,
        NOW);
    if (method != PaymentMethod.COD) {
      o.markPaid("pay-1", NOW);
    }
    o.advance(OrderStatus.DELIVERED, "delivered", null, null, NOW);
    return o;
  }

  private OrderReturn pickedUp(Order o, int qty, String refundTo) {
    OrderReturn r =
        o.requestReturn(
            List.of(new ReturnLine(P, null, qty)),
            ReturnReason.DAMAGED,
            null,
            refundTo,
            null,
            7,
            NOW);
    r.decide(true, null, NOW);
    r.markPickedUp(NOW);
    return r;
  }

  private ReturnRefundPlan freeze(OrderReturn r) {
    when(returnsRepo.findById(any())).thenReturn(Optional.of(r));
    when(orderRepo.lockById(any())).thenReturn(Optional.of(r.getOrder()));
    return store.freezeLegs(UUID.randomUUID());
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    // name, method, qty, price, discount, coins, shipping, wallet, returnQty, refundTo, R, w, rest,
    // route
    "card only,CARD,3,100.00,0,0,0,0,1,SOURCE,100.00,0.00,100.00,PAYMENT_SOURCE",
    "wallet only,WALLET,3,100.00,0,0,0,300,1,SOURCE,100.00,100.00,0.00,NONE",
    "mixed thirds,CARD,3,100.00,0,0,0,100,1,SOURCE,100.00,33.33,66.67,PAYMENT_SOURCE",
    "coins on card,CARD,3,100.00,0,30,0,0,3,SOURCE,270.00,0.00,270.00,PAYMENT_SOURCE",
    "coins and wallet,CARD,3,100.00,0,30,0,90,3,SOURCE,270.00,90.00,180.00,PAYMENT_SOURCE",
    "completing return with shipping,CARD,1,300.00,0,0,40,100,1,SOURCE,340.00,100.00,240.00,PAYMENT_SOURCE",
    "cod goes to wallet,COD,1,100.00,0,0,0,0,1,SOURCE,100.00,0.00,100.00,PAYMENT_WALLET",
    "refundTo wallet on card,CARD,1,100.00,0,0,0,0,1,WALLET,100.00,0.00,100.00,PAYMENT_WALLET",
    "zero refund,COD,1,100.00,100,0,0,0,1,SOURCE,0.00,0.00,0.00,NONE"
  })
  @DisplayName("a single return freezes legs that add up to the refund, on the right route")
  void single(
      String name,
      PaymentMethod method,
      int qty,
      String price,
      String discount,
      String coins,
      String shipping,
      String wallet,
      int returnQty,
      String refundTo,
      String refund,
      String w,
      String rest,
      String route) {
    Order o = order(method, qty, price, discount, coins, shipping, wallet);
    OrderReturn r = pickedUp(o, returnQty, refundTo);

    ReturnRefundPlan plan = freeze(r);

    assertThat(r.getRefundAmount()).isEqualByComparingTo(refund);
    assertThat(plan.wallet()).isEqualByComparingTo(w);
    assertThat(plan.rest()).isEqualByComparingTo(rest);
    assertThat(plan.route()).isEqualTo(route);
    assertThat(plan.wallet().add(plan.rest())).isEqualByComparingTo(r.getRefundAmount());
  }

  @Test
  @DisplayName("three sequential partial returns end on the last cent: wallet 100.00, card 200.00")
  void sequentialPartials() {
    Order o = order(PaymentMethod.CARD, 3, "100.00", "0", "0", "0", "100");
    BigDecimal wallet = BigDecimal.ZERO;
    BigDecimal card = BigDecimal.ZERO;
    for (int i = 0; i < 3; i++) {
      ReturnRefundPlan plan = freeze(pickedUp(o, 1, "SOURCE"));
      assertThat(plan.wallet().add(plan.rest())).isEqualByComparingTo("100.00");
      wallet = wallet.add(plan.wallet());
      card = card.add(plan.rest());
    }
    assertThat(wallet).isEqualByComparingTo("100.00");
    assertThat(card).isEqualByComparingTo("200.00");
  }

  @Test
  @DisplayName("a REFUNDED sibling from before V13 counts with the legacy split")
  void legacySibling() {
    Order o = order(PaymentMethod.CARD, 3, "100.00", "0", "0", "0", "100");
    OrderReturn old = pickedUp(o, 1, "SOURCE");
    old.markRefunded(NOW);

    ReturnRefundPlan plan = freeze(pickedUp(o, 1, "SOURCE"));

    assertThat(plan.wallet()).isEqualByComparingTo("33.33");
    assertThat(plan.rest()).isEqualByComparingTo("66.67");
  }

  @Test
  @DisplayName(
      "the shipping-carrying return refunded first does not close the order or the other return")
  void completingReturnRefundedFirst() {
    Order o = order(PaymentMethod.CARD, 3, "100.00", "0", "0", "40.00", "0");
    OrderReturn partial = pickedUp(o, 1, "SOURCE");
    OrderReturn completing = pickedUp(o, 2, "SOURCE");
    assertThat(completing.getShippingRefund()).isEqualByComparingTo("40.00");

    freeze(completing);
    when(returnsRepo.findById(any())).thenReturn(Optional.of(completing));
    store.completeRefund(UUID.randomUUID());

    assertThat(o.getPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    ReturnRefundPlan plan = freeze(partial);
    assertThat(plan.route()).isEqualTo("PAYMENT_SOURCE");
    assertThat(plan.rest()).isEqualByComparingTo("100.00");

    when(returnsRepo.findById(any())).thenReturn(Optional.of(partial));
    store.completeRefund(UUID.randomUUID());
    assertThat(o.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
  }

  @Test
  @DisplayName("freezing twice keeps the first legs")
  void noOpWhenFrozen() {
    Order o = order(PaymentMethod.CARD, 3, "100.00", "0", "0", "0", "100");
    OrderReturn r = pickedUp(o, 1, "SOURCE");
    ReturnRefundPlan first = freeze(r);

    ReturnRefundPlan second = freeze(r);

    assertThat(second.wallet()).isEqualByComparingTo(first.wallet());
    assertThat(second.rest()).isEqualByComparingTo(first.rest());
  }
}
