package com.acme.ecommerce.seller.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.seller.support.AbstractSellerIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Seller ledger and payouts on a real database (TD-P9-01 money paths): postings are idempotent per
 * event, return reversals book once, and a payout run respects the hold and never pays twice.
 */
class LedgerIntegrationTest extends AbstractSellerIntegrationTest {

  @Autowired private LedgerService ledger;
  @Autowired private JdbcClient jdbc;

  /** An APPROVED seller with the given commission. */
  private UUID seller(String commissionPercent) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO sellers (id, user_id, display_name, slug, status, commission_percent)"
                + " VALUES (:i, :u, 'Test Seller', :s, 'APPROVED', :c)")
        .param("i", id)
        .param("u", UUID.randomUUID())
        .param("s", "test-" + id)
        .param("c", new BigDecimal(commissionPercent))
        .update();
    return id;
  }

  private LedgerService.Part part(UUID shipment, UUID seller, String amount) {
    return new LedgerService.Part(
        shipment.toString(), seller.toString(), "DELIVERED", new BigDecimal(amount), List.of());
  }

  private BigDecimal balance(UUID seller) {
    return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE seller_id = :s")
        .param("s", seller)
        .query(BigDecimal.class)
        .single();
  }

  private long entries(UUID seller, String type) {
    return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE seller_id = :s AND type = :t")
        .param("s", seller)
        .param("t", type)
        .query(Long.class)
        .single();
  }

  @Test
  @DisplayName("a delivered shipment books sale, commission and fee once, even when replayed")
  void deliveryIsIdempotent() {
    UUID s = seller("10");
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();

    ledger.recordDelivered(order, part(shipment, s, "1000.00"), "INR");
    ledger.recordDelivered(order, part(shipment, s, "1000.00"), "INR");

    assertThat(entries(s, "SALE")).isEqualTo(1);
    assertThat(entries(s, "COMMISSION")).isEqualTo(1);
    assertThat(entries(s, "FEE")).isEqualTo(1);
    // 1000 - 10% commission - 10 fee
    assertThat(balance(s)).isEqualByComparingTo("890.00");
  }

  @Test
  @DisplayName("a return refund reverses the sale and its commission once per return")
  void returnReversalOnce() {
    UUID s = seller("10");
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    UUID returnId = UUID.randomUUID();
    ledger.recordDelivered(order, part(shipment, s, "1000.00"), "INR");

    ledger.reverseReturn(order, shipment, returnId, new BigDecimal("400.00"), "INR");
    ledger.reverseReturn(order, shipment, returnId, new BigDecimal("400.00"), "INR");

    assertThat(entries(s, "REFUND_REVERSAL")).isEqualTo(1);
    assertThat(entries(s, "COMMISSION_REVERSAL")).isEqualTo(1);
    // 890 - 400 refunded + 40 commission given back
    assertThat(balance(s)).isEqualByComparingTo("530.00");
  }

  @Test
  @DisplayName("a payout run respects the hold, pays the balance once and never pays it again")
  void payoutRespectsHoldAndPaysOnce() {
    UUID s = seller("10");
    ledger.recordDelivered(UUID.randomUUID(), part(UUID.randomUUID(), s, "500.00"), "INR");

    ledger.run(7); // entries are minutes old: inside the 7-day hold
    assertThat(paidTo(s)).isEmpty();

    jdbc.sql(
            "UPDATE ledger_entries SET created_at = now() - interval '8 days' WHERE seller_id = :s")
        .param("s", s)
        .update();
    ledger.run(7);
    ledger.run(7);

    List<BigDecimal> paid = paidTo(s);
    assertThat(paid).hasSize(1);
    assertThat(paid.get(0)).isEqualByComparingTo("440.00"); // 500 - 50 - 10
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM ledger_entries WHERE seller_id = :s AND payout_id IS NULL")
                .param("s", s)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  @DisplayName("a zero balance (fully reversed shipment) is not paid out")
  void fullyReversedShipmentIsNotPaidOut() {
    UUID s = seller("10");
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    ledger.recordDelivered(order, part(shipment, s, "100.00"), "INR");
    ledger.reverseReturn(order, shipment, UUID.randomUUID(), new BigDecimal("100.00"), "INR");
    jdbc.sql(
            "UPDATE ledger_entries SET created_at = now() - interval '8 days' WHERE seller_id = :s")
        .param("s", s)
        .update();

    ledger.run(7);

    // sale 100 - commission 10 - fee 10 + commission back 10 + fee back 10 (fully reversed)
    assertThat(balance(s)).isEqualByComparingTo("0.00");
    assertThat(paidTo(s)).isEmpty();
  }

  private List<BigDecimal> paidTo(UUID seller) {
    return jdbc.sql("SELECT amount FROM payouts WHERE seller_id = :s")
        .param("s", seller)
        .query(BigDecimal.class)
        .list();
  }
}
