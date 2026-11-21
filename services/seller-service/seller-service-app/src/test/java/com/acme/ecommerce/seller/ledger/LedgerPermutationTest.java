package com.acme.ecommerce.seller.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.seller.support.AbstractSellerIntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** AC-13: the net per shipment does not depend on the order events arrive in. */
class LedgerPermutationTest extends AbstractSellerIntegrationTest {

  private static final String R1 = "R1";
  private static final String R2 = "R2";
  private static final String R1_REPLAY = "R1_REPLAY";
  private static final String LEGACY = "LEGACY";
  private static final String DELIVERED_REPLAY = "DELIVERED_REPLAY";

  @Autowired private LedgerService ledger;
  @Autowired private JdbcClient jdbc;

  private UUID seller() {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO sellers (id, user_id, display_name, slug, status, commission_percent)"
                + " VALUES (:i, :u, 'Perm Seller', :s, 'APPROVED', 12.5)")
        .param("i", id)
        .param("u", UUID.randomUUID())
        .param("s", "perm-" + id)
        .update();
    return id;
  }

  private BigDecimal net(UUID shipment) {
    return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE shipment_id = :s")
        .param("s", shipment)
        .query(BigDecimal.class)
        .single();
  }

  private void deliver(UUID order, UUID shipment, UUID seller) {
    ledger.recordDelivered(
        order,
        new LedgerService.Part(
            shipment.toString(),
            seller.toString(),
            "DELIVERED",
            new BigDecimal("100.01"),
            List.of()),
        "INR");
  }

  private void apply(String event, UUID seller, UUID order, UUID shipment, UUID r1, UUID r2) {
    switch (event) {
      case R1, R1_REPLAY ->
          ledger.reverseReturn(order, shipment, r1, new BigDecimal("33.33"), "INR");
      case R2 -> ledger.reverseReturn(order, shipment, r2, new BigDecimal("66.68"), "INR");
      case LEGACY -> ledger.reverseRefund(order, List.of(shipment));
      case DELIVERED_REPLAY -> deliver(order, shipment, seller);
      default -> throw new IllegalArgumentException(event);
    }
  }

  private static void permute(List<String> rest, List<String> acc, List<List<String>> out) {
    if (rest.isEmpty()) {
      out.add(new ArrayList<>(acc));
      return;
    }
    for (int i = 0; i < rest.size(); i++) {
      List<String> next = new ArrayList<>(rest);
      acc.add(next.remove(i));
      permute(next, acc, out);
      acc.remove(acc.size() - 1);
    }
  }

  @Test
  @DisplayName("all 120 orders of {R1, R2, R1 replay, legacy whole, DELIVERED replay} net to zero")
  void everyOrderNetsToZero() {
    List<List<String>> orders = new ArrayList<>();
    permute(List.of(R1, R2, R1_REPLAY, LEGACY, DELIVERED_REPLAY), new ArrayList<>(), orders);
    assertThat(orders).hasSize(120).doesNotHaveDuplicates();
    for (List<String> order : orders) {
      UUID seller = seller();
      UUID orderId = UUID.randomUUID();
      UUID shipment = UUID.randomUUID();
      UUID r1 = UUID.randomUUID();
      UUID r2 = UUID.randomUUID();
      deliver(orderId, shipment, seller);
      order.forEach(e -> apply(e, seller, orderId, shipment, r1, r2));
      assertThat(net(shipment)).as("net for order %s", order).isEqualByComparingTo("0.00");
      assertThat(
              jdbc.sql(
                      "SELECT count(*) FROM ledger_entries WHERE shipment_id = :s AND type = 'FEE_REVERSAL'")
                  .param("s", shipment)
                  .query(Long.class)
                  .single())
          .as("fee reversed once for %s", order)
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName("the {R1}-only subset nets S - C - F - R1 + c1 (positive control for the zero net)")
  void r1OnlyLeavesTheExpectedNet() {
    UUID seller = seller();
    UUID orderId = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    deliver(orderId, shipment, seller);
    apply(R1, seller, orderId, shipment, UUID.randomUUID(), UUID.randomUUID());
    // S 100.01, C 12.50, F 10.00, R1 33.33, c1 = round(33.33 x 12.50 / 100.01) = 4.17
    assertThat(net(shipment)).isEqualByComparingTo("48.35");
  }
}
