package com.acme.ecommerce.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.order.domain.Order.Allocation;
import com.acme.ecommerce.order.domain.Order.ReturnLine;
import com.acme.ecommerce.order.domain.Order.ShipTo;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * O-05a AC-1..3: the return that completes the whole order refunds shipping, exactly once among the
 * live (non-rejected) returns, whichever shipments the returns came from.
 */
class ReturnShippingRuleTest {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private static final UUID A = UUID.randomUUID();
  private static final UUID B = UUID.randomUUID();

  /** Two lines (A and B, 2 x 100 and 2 x 50 = 300) plus the given shipping fee, delivered. */
  private static Order order(String shipping) {
    Order o =
        Order.draft(
            UUID.randomUUID(),
            "a@b.c",
            "k",
            UUID.randomUUID(),
            new ShipTo("N", "1", "l1", null, "c", "s", "560001", "IN"),
            "INR",
            PaymentMethod.COD,
            NOW);
    o.addItem(A, null, null, "sku-a", "A", new BigDecimal("100.00"), 2, null);
    o.addItem(B, null, null, "sku-b", "B", new BigDecimal("50.00"), 2, null);
    BigDecimal s = new BigDecimal(shipping);
    BigDecimal sub = new BigDecimal("300.00");
    o.begin(
        new Pricing(
            sub,
            BigDecimal.ZERO,
            s,
            sub.add(s),
            BigDecimal.ZERO,
            null,
            null,
            null,
            null,
            BigDecimal.ZERO,
            false),
        null,
        null,
        NOW);
    o.advance(OrderStatus.DELIVERED, "delivered", null, null, NOW);
    return o;
  }

  private static OrderReturn ret(Order o, ReturnLine... lines) {
    return o.requestReturn(List.of(lines), ReturnReason.DAMAGED, null, "SOURCE", null, 7, NOW);
  }

  private static ReturnLine a(int q) {
    return new ReturnLine(A, null, q);
  }

  private static ReturnLine b(int q) {
    return new ReturnLine(B, null, q);
  }

  private static void assertShipping(OrderReturn r, String shipping, String refund) {
    assertThat(r.getShippingRefund()).isEqualByComparingTo(shipping);
    assertThat(r.getRefundAmount()).isEqualByComparingTo(refund);
  }

  @Test
  @DisplayName("AC-1: a single return of everything refunds items plus shipping")
  void singleFullReturn() {
    assertShipping(ret(order("40.00"), a(2), b(2)), "40.00", "340.00");
  }

  @Test
  @DisplayName("AC-2: a partial return refunds no shipping")
  void partialReturn() {
    assertShipping(ret(order("40.00"), a(1)), "0", "100.00");
  }

  @Test
  @DisplayName("a free-shipping order (S = 0) never carries shipping")
  void freeShipping() {
    assertShipping(ret(order("0.00"), a(2), b(2)), "0", "300.00");
  }

  @Test
  @DisplayName("three partial returns: only the one that completes the order carries shipping")
  void threePartials() {
    Order o = order("40.00");
    OrderReturn r1 = ret(o, a(1));
    OrderReturn r2 = ret(o, a(1), b(1));
    OrderReturn r3 = ret(o, b(1));

    assertShipping(r1, "0", "100.00");
    assertShipping(r2, "0", "150.00");
    assertShipping(r3, "40.00", "90.00");
  }

  @Test
  @DisplayName("a rejected shipping carrier hands shipping to the next completing return, once")
  void rejectedCarrierMovesShipping() {
    Order o = order("40.00");
    OrderReturn first = ret(o, a(2));
    OrderReturn carrier = ret(o, b(2));
    assertThat(first.getShippingRefund()).isEqualByComparingTo("0");
    assertThat(carrier.getShippingRefund()).isEqualByComparingTo("40.00");

    carrier.decide(false, "no", NOW);
    OrderReturn again = ret(o, b(2));

    assertThat(again.getShippingRefund()).isEqualByComparingTo("40.00");
    long live =
        o.getReturns().stream()
            .filter(r -> r.getStatus() != ReturnStatus.REJECTED)
            .filter(r -> r.getShippingRefund().signum() > 0)
            .count();
    assertThat(live).isEqualTo(1);
  }

  @Test
  @DisplayName("AC-3: two shipment returns that sum to the whole order refund shipping once")
  void twoShipmentReturns() {
    UUID w1 = UUID.randomUUID();
    UUID w2 = UUID.randomUUID();
    Order o =
        Order.draft(
            UUID.randomUUID(),
            "a@b.c",
            "k2",
            UUID.randomUUID(),
            new ShipTo("N", "1", "l1", null, "c", "s", "560001", "IN"),
            "INR",
            PaymentMethod.COD,
            NOW);
    o.addItem(A, null, null, "sku-a", "A", new BigDecimal("100.00"), 2, null);
    o.addItem(B, null, null, "sku-b", "B", new BigDecimal("50.00"), 2, null);
    BigDecimal sub = new BigDecimal("300.00");
    BigDecimal ship = new BigDecimal("40.00");
    o.begin(
        new Pricing(
            sub,
            BigDecimal.ZERO,
            ship,
            sub.add(ship),
            BigDecimal.ZERO,
            null,
            null,
            null,
            null,
            BigDecimal.ZERO,
            false),
        null,
        null,
        NOW);
    o.assignShipments(
        List.of(
            new Allocation(A, null, null, w1, "WH1", "560001", 2),
            new Allocation(B, null, null, w2, "WH2", "560001", 2)),
        Map.of(),
        pin -> null,
        NOW);
    assertThat(o.getShipments()).hasSize(2);
    o.getShipments().forEach(s -> ReflectionTestUtils.setField(s, "id", UUID.randomUUID()));
    o.getShipments()
        .forEach(
            s ->
                o.advanceShipment(
                    s.getId(), OrderStatus.DELIVERED, "d", null, null, HistoryActor.ADMIN, NOW));
    UUID shipA =
        o.getItems().stream()
            .filter(i -> i.getProductId().equals(A))
            .findFirst()
            .orElseThrow()
            .getShipmentId();
    UUID shipB =
        o.getItems().stream()
            .filter(i -> i.getProductId().equals(B))
            .findFirst()
            .orElseThrow()
            .getShipmentId();

    OrderReturn first =
        o.requestReturn(List.of(a(2)), ReturnReason.DAMAGED, null, "SOURCE", shipA, 7, NOW);
    OrderReturn second =
        o.requestReturn(List.of(b(2)), ReturnReason.DAMAGED, null, "SOURCE", shipB, 7, NOW);

    assertShipping(first, "0", "200.00");
    assertShipping(second, "40.00", "140.00");
    assertThat(first.getShippingRefund().add(second.getShippingRefund()))
        .isEqualByComparingTo("40.00");
  }
}
