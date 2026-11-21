package com.acme.ecommerce.seller.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.seller.support.AbstractSellerIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Capped, fee-aware reversals on a real database (F-20261119-13, AC-1..AC-5). */
@ExtendWith(OutputCaptureExtension.class)
class LedgerReversalIntegrationTest extends AbstractSellerIntegrationTest {

  @Autowired private LedgerService ledger;
  @Autowired private JdbcClient jdbc;

  private UUID seller(String commissionPercent) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO sellers (id, user_id, display_name, slug, status, commission_percent)"
                + " VALUES (:i, :u, 'Test Seller', :s, 'APPROVED', :c)")
        .param("i", id)
        .param("u", UUID.randomUUID())
        .param("s", "rev-" + id)
        .param("c", new BigDecimal(commissionPercent))
        .update();
    return id;
  }

  private void deliver(UUID order, UUID shipment, UUID seller, String amount, String currency) {
    ledger.recordDelivered(
        order,
        new LedgerService.Part(
            shipment.toString(), seller.toString(), "DELIVERED", new BigDecimal(amount), List.of()),
        currency);
  }

  private BigDecimal sum(UUID shipment, String type) {
    return jdbc.sql(
            "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE shipment_id = :s AND type = :t")
        .param("s", shipment)
        .param("t", type)
        .query(BigDecimal.class)
        .single();
  }

  private long count(UUID shipment, String type) {
    return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE shipment_id = :s AND type = :t")
        .param("s", shipment)
        .param("t", type)
        .query(Long.class)
        .single();
  }

  private BigDecimal net(UUID shipment) {
    return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE shipment_id = :s")
        .param("s", shipment)
        .query(BigDecimal.class)
        .single();
  }

  @Test
  @DisplayName("a whole return reverses the booked fee once, in the fee's currency")
  void wholeReturnReversesBookedFeeOnce() {
    UUID s = seller("10");
    UUID order = UUID.randomUUID();
    UUID inr = UUID.randomUUID();
    UUID usd = UUID.randomUUID();
    UUID free = UUID.randomUUID();
    UUID platformLike = seller("0");
    deliver(order, inr, s, "1000.00", "INR");
    deliver(order, usd, s, "100.00", "USD");
    deliver(order, free, platformLike, "100.00", "USD");
    UUID r1 = UUID.randomUUID();
    UUID r2 = UUID.randomUUID();
    UUID r3 = UUID.randomUUID();

    for (int i = 0; i < 2; i++) { // replay
      ledger.reverseReturn(order, inr, r1, new BigDecimal("1000.00"), "INR");
      ledger.reverseReturn(order, usd, r2, new BigDecimal("100.00"), "USD");
      ledger.reverseReturn(order, free, r3, new BigDecimal("100.00"), "USD");
    }

    assertThat(count(inr, "FEE_REVERSAL")).isEqualTo(1);
    assertThat(sum(inr, "FEE_REVERSAL")).isEqualByComparingTo("10.00");
    assertThat(sum(usd, "FEE_REVERSAL")).isEqualByComparingTo("0.25");
    assertThat(count(free, "FEE_REVERSAL")).isZero();
    assertThat(net(inr)).isEqualByComparingTo("0.00");
    assertThat(net(usd)).isEqualByComparingTo("0.00");
    assertThat(net(free)).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName(
      "partial returns give the fee back only when the cumulative reversal covers the sale")
  void partialReturnsReverseFeeOnlyWhenCumulativeCoversShipment() {
    UUID s = seller("10");
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    deliver(order, shipment, s, "100.00", "INR");

    ledger.reverseReturn(order, shipment, UUID.randomUUID(), new BigDecimal("30.00"), "INR");
    assertThat(count(shipment, "FEE_REVERSAL")).isZero();
    assertThat(sum(shipment, "COMMISSION_REVERSAL")).isEqualByComparingTo("3.00");

    ledger.reverseReturn(order, shipment, UUID.randomUUID(), new BigDecimal("70.00"), "INR");
    assertThat(count(shipment, "FEE_REVERSAL")).isEqualTo(1);
    assertThat(sum(shipment, "COMMISSION_REVERSAL")).isEqualByComparingTo("10.00");
    assertThat(sum(shipment, "REFUND_REVERSAL")).isEqualByComparingTo("-100.00");
    assertThat(net(shipment)).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("the reversal is the items value at the booked commission ratio")
  void reversalUsesItemsValueNotRefund() {
    UUID s = seller("10");
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    deliver(order, shipment, s, "200.00", "INR");
    // the customer was refunded 95 for items worth 100; only the items value is the seller's
    jdbc.sql("UPDATE sellers SET commission_percent = 20 WHERE id = :s").param("s", s).update();

    ledger.reverseReturn(order, shipment, UUID.randomUUID(), new BigDecimal("100.00"), "INR");

    assertThat(sum(shipment, "REFUND_REVERSAL")).isEqualByComparingTo("-100.00");
    assertThat(sum(shipment, "COMMISSION_REVERSAL")).isEqualByComparingTo("10.00"); // booked 10%
  }

  @Test
  @DisplayName("a reversal above what is left is capped and warned about")
  void reversalCappedAtRemainderAndWarns(CapturedOutput output) {
    UUID s = seller("10");
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    deliver(order, shipment, s, "100.00", "INR");

    ledger.reverseReturn(order, shipment, UUID.randomUUID(), new BigDecimal("70.00"), "INR");
    ledger.reverseReturn(order, shipment, UUID.randomUUID(), new BigDecimal("60.00"), "INR");
    ledger.reverseReturn(order, shipment, UUID.randomUUID(), new BigDecimal("5.00"), "INR");

    assertThat(sum(shipment, "REFUND_REVERSAL")).isEqualByComparingTo("-100.00");
    assertThat(count(shipment, "REFUND_REVERSAL")).isEqualTo(2); // the third posts nothing
    assertThat(sum(shipment, "COMMISSION_REVERSAL")).isEqualByComparingTo("10.00");
    assertThat(output.getOut()).contains("capped to the remaining 30.00");
  }

  @Test
  @DisplayName("two concurrent returns share one cap")
  void concurrentReturnsShareCap() throws Exception {
    UUID s = seller("10");
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    deliver(order, shipment, s, "100.00", "INR");
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> fs =
          List.of(
              pool.submit(() -> concurrentReturn(go, order, shipment)),
              pool.submit(() -> concurrentReturn(go, order, shipment)));
      go.countDown();
      for (Future<?> f : fs) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(sum(shipment, "REFUND_REVERSAL")).isEqualByComparingTo("-100.00");
    assertThat(sum(shipment, "COMMISSION_REVERSAL")).isEqualByComparingTo("10.00");
    assertThat(count(shipment, "FEE_REVERSAL")).isEqualTo(1);
  }

  private void concurrentReturn(CountDownLatch go, UUID order, UUID shipment) {
    try {
      go.await();
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
    ledger.reverseReturn(order, shipment, UUID.randomUUID(), new BigDecimal("60.00"), "INR");
  }

  @Test
  @DisplayName("a whole-shipment reversal after a per-return one posts only the remainder")
  void wholeShipmentAfterPerReturnPostsRemainderOnly() {
    UUID s = seller("10");
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    deliver(order, shipment, s, "100.00", "INR");
    ledger.reverseReturn(order, shipment, UUID.randomUUID(), new BigDecimal("30.00"), "INR");

    ledger.reverseRefund(order, null);
    ledger.reverseRefund(order, List.of(shipment)); // replay

    assertThat(sum(shipment, "REFUND_REVERSAL")).isEqualByComparingTo("-100.00");
    assertThat(sum(shipment, "COMMISSION_REVERSAL")).isEqualByComparingTo("10.00");
    assertThat(count(shipment, "FEE_REVERSAL")).isEqualTo(1);
    assertThat(net(shipment)).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("a per-return reversal after a whole-shipment one posts nothing")
  void perReturnAfterLegacyReversalPostsNothing() {
    UUID s = seller("10");
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    deliver(order, shipment, s, "100.00", "INR");
    ledger.reverseRefund(order, null);
    long before = count(shipment, "REFUND_REVERSAL");

    ledger.reverseReturn(order, shipment, UUID.randomUUID(), new BigDecimal("40.00"), "INR");

    assertThat(before).isEqualTo(1);
    assertThat(count(shipment, "REFUND_REVERSAL")).isEqualTo(1);
    assertThat(sum(shipment, "REFUND_REVERSAL")).isEqualByComparingTo("-100.00");
    assertThat(count(shipment, "COMMISSION_REVERSAL")).isEqualTo(1);
    assertThat(count(shipment, "FEE_REVERSAL")).isEqualTo(1);
  }

  @Test
  @DisplayName("a reversal naming another order than the SALE's is rejected and posts nothing")
  void reversalForAnotherOrderIsRejected() {
    UUID s = seller("10");
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    deliver(order, shipment, s, "100.00", "INR");

    assertThatThrownBy(
            () ->
                ledger.reverseReturn(
                    UUID.randomUUID(), shipment, UUID.randomUUID(), new BigDecimal("40.00"), "INR"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(count(shipment, "REFUND_REVERSAL")).isZero();
    assertThat(count(shipment, "COMMISSION_REVERSAL")).isZero();
  }

  @Test
  @DisplayName("a reversal with no SALE posts nothing and is reported once")
  void reversalWithoutSaleWritesUnmatchedRow() {
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    UUID returnId = UUID.randomUUID();

    ledger.reverseReturn(order, shipment, returnId, new BigDecimal("42.00"), "INR");
    ledger.reverseReturn(order, shipment, returnId, new BigDecimal("42.00"), "INR");

    assertThat(
            jdbc.sql("SELECT count(*) FROM ledger_entries WHERE shipment_id = :s")
                .param("s", shipment)
                .query(Long.class)
                .single())
        .isZero();
    var rows =
        jdbc.sql(
                "SELECT ref, expected FROM ledger_discrepancies WHERE shipment_id = :s AND kind = 'UNMATCHED_REVERSAL'")
            .param("s", shipment)
            .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getBigDecimal(2)})
            .list();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)[0]).isEqualTo(returnId);
    assertThat((BigDecimal) rows.get(0)[1]).isEqualByComparingTo("42.00");
  }

  @Autowired private javax.sql.DataSource dataSource;

  @Test
  @DisplayName("a reversal waits on a held SALE row lock, then reads the committed state and caps")
  void reversalWaitsOnHeldSaleLockThenCompletes() throws Exception {
    UUID s = seller("10");
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    deliver(order, shipment, s, "100.00", "INR");
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (java.sql.Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (var st = holder.createStatement()) {
        st.execute(
            "SELECT 1 FROM ledger_entries WHERE shipment_id = '"
                + shipment
                + "' AND type = 'SALE' AND return_id IS NULL FOR UPDATE");
        // while holding the lock, a competing reversal of 70 is booked but not yet committed
        st.execute(
            "INSERT INTO ledger_entries (seller_id, type, amount, currency, order_id, shipment_id, return_id)"
                + " VALUES ('"
                + s
                + "', 'REFUND_REVERSAL', -70.00, 'INR', '"
                + order
                + "', '"
                + shipment
                + "', '"
                + UUID.randomUUID()
                + "')");
      }
      Future<?> waiting =
          pool.submit(
              () ->
                  ledger.reverseReturn(
                      order, shipment, UUID.randomUUID(), new BigDecimal("60.00"), "INR"));

      assertThatThrownBy(() -> waiting.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS))
          .isInstanceOf(java.util.concurrent.TimeoutException.class);
      assertThat(waiting.isDone()).isFalse();

      holder.commit();
      waiting.get(10, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    // 70 + capped 30, not 70 + 60
    assertThat(sum(shipment, "REFUND_REVERSAL")).isEqualByComparingTo("-100.00");
    assertThat(sum(shipment, "COMMISSION_REVERSAL"))
        .isEqualByComparingTo("10.00"); // completing: the rest of the booked commission
    assertThat(count(shipment, "FEE_REVERSAL")).isEqualTo(1);
  }

  @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

  private List<Object[]> register(UUID shipment) {
    return jdbc.sql("SELECT status, amount FROM ledger_shipments WHERE shipment_id = :s")
        .param("s", shipment)
        .query((rs, n) -> new Object[] {rs.getString(1), rs.getBigDecimal(2)})
        .list();
  }

  private LedgerService.Part registerPart(
      UUID shipment, UUID seller, String status, String amount) {
    return new LedgerService.Part(
        shipment.toString(), seller.toString(), status, new BigDecimal(amount), List.of());
  }

  @Test
  @DisplayName("the register row survives a DELIVERED whose posting fails and rolls back")
  void registerSurvivesFailedPosting() {
    UUID s = seller("10");
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    // a bad product id makes recordDelivered throw after it already posted the SALE
    LedgerService.Part bad =
        new LedgerService.Part(
            shipment.toString(),
            s.toString(),
            "DELIVERED",
            new BigDecimal("100.00"),
            List.of(new LedgerService.Item("not-a-uuid", "x", 1, new BigDecimal("100.00"))));

    // even inside one outer transaction that rolls back, the register has already committed
    assertThatThrownBy(
            () ->
                new org.springframework.transaction.support.TransactionTemplate(txManager)
                    .executeWithoutResult(
                        st -> {
                          ledger.track(order, List.of(bad), "INR");
                          ledger.recordDelivered(order, bad, "INR");
                        }))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(count(shipment, "SALE")).isZero();
    assertThat(register(shipment)).hasSize(1);
    assertThat(register(shipment).get(0)[0]).isEqualTo("DELIVERED");
    assertThat((BigDecimal) register(shipment).get(0)[1]).isEqualByComparingTo("100.00");
  }

  @Test
  @DisplayName("an older event never moves the register status or amount backwards")
  void registerNeverMovesBackwards() {
    UUID s = seller("10");
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();

    ledger.track(order, List.of(registerPart(shipment, s, "PLACED", "90.00")), "INR");
    ledger.track(order, List.of(registerPart(shipment, s, "DELIVERED", "100.00")), "INR");
    ledger.track(order, List.of(registerPart(shipment, s, "SHIPPED", "90.00")), "INR");

    assertThat(register(shipment)).hasSize(1);
    assertThat(register(shipment).get(0)[0]).isEqualTo("DELIVERED");
    assertThat((BigDecimal) register(shipment).get(0)[1]).isEqualByComparingTo("100.00");
  }
}
