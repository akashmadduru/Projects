package com.acme.ecommerce.seller.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.ResourceConflictException;
import com.acme.ecommerce.common.web.audit.AuditPublisher;
import com.acme.ecommerce.seller.client.OrderFeignClient;
import com.acme.ecommerce.seller.client.OrderFeignClient.ShipmentInfo;
import com.acme.ecommerce.seller.ledger.LedgerService.Item;
import com.acme.ecommerce.seller.ledger.LedgerService.Part;
import com.acme.ecommerce.seller.ledger.OrderEventConsumer.OrderEvt;
import com.acme.ecommerce.seller.support.AbstractSellerIntegrationTest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** A CANCELLED shipment is terminal in the register and books nothing (F-20261119-16). */
@ExtendWith(OutputCaptureExtension.class)
class LedgerCancelledShipmentIntegrationTest extends AbstractSellerIntegrationTest {

  @Autowired private LedgerService ledger;
  @Autowired private LedgerReconciler reconciler;
  @Autowired private JdbcClient jdbc;
  @Autowired private OrderEventConsumer.Listener listener;
  @MockitoBean private OrderFeignClient orders;
  @MockitoBean private AuditPublisher auditPublisher;

  @BeforeEach
  void orderServiceAnswersByDefault() {
    when(orders.shipment(any()))
        .thenAnswer(
            inv -> {
              UUID sh = inv.getArgument(0);
              return new ShipmentInfo(
                  sh, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "DELIVERED");
            });
  }

  // ---- helpers ----------------------------------------------------------------------------

  private UUID seller() {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO sellers (id, user_id, display_name, slug, status, commission_percent)"
                + " VALUES (:i, :u, 'Cancel Seller', :s, 'APPROVED', 10)")
        .param("i", id)
        .param("u", UUID.randomUUID())
        .param("s", "can-" + id)
        .update();
    return id;
  }

  private static Part part(UUID shipment, UUID seller, String status, String amount) {
    return new Part(
        shipment.toString(),
        seller.toString(),
        status,
        new BigDecimal(amount),
        List.of(new Item(UUID.randomUUID().toString(), "Secret Widget", 1, new BigDecimal(amount))));
  }

  private void consume(String type, UUID order, String status, List<Part> parts) {
    listener.onEvent(
        new OrderEvt(
            type,
            order.toString(),
            status,
            "PAID",
            new BigDecimal("100.00"),
            "INR",
            parts,
            null,
            null,
            null,
            null,
            null));
  }

  private void consumeOne(UUID order, UUID shipment, UUID seller, String status, String amount) {
    consume("SHIPMENT_STATUS_CHANGED", order, status, List.of(part(shipment, seller, status, amount)));
  }

  private long entries(UUID shipment, String type) {
    return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE shipment_id = :s AND type = :t")
        .param("s", shipment)
        .param("t", type)
        .query(Long.class)
        .single();
  }

  private long allEntries(UUID shipment) {
    return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE shipment_id = :s")
        .param("s", shipment)
        .query(Long.class)
        .single();
  }

  private long saleLines(UUID shipment) {
    return jdbc.sql("SELECT count(*) FROM sale_lines WHERE shipment_id = :s")
        .param("s", shipment)
        .query(Long.class)
        .single();
  }

  private String regStatus(UUID shipment) {
    return jdbc.sql("SELECT status FROM ledger_shipments WHERE shipment_id = :s")
        .param("s", shipment)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private BigDecimal regAmount(UUID shipment) {
    return jdbc.sql("SELECT amount FROM ledger_shipments WHERE shipment_id = :s")
        .param("s", shipment)
        .query(BigDecimal.class)
        .single();
  }

  private UUID regSeller(UUID shipment) {
    return jdbc.sql("SELECT seller_id FROM ledger_shipments WHERE shipment_id = :s")
        .param("s", shipment)
        .query(UUID.class)
        .single();
  }

  private OffsetDateTime regUpdatedAt(UUID shipment) {
    return jdbc.sql("SELECT updated_at FROM ledger_shipments WHERE shipment_id = :s")
        .param("s", shipment)
        .query(OffsetDateTime.class)
        .single();
  }

  private void age(UUID shipment) {
    jdbc.sql(
            "UPDATE ledger_shipments SET updated_at = now() - interval '2 hours' WHERE shipment_id = :s")
        .param("s", shipment)
        .update();
  }

  private long found(UUID shipment, String kind) {
    return jdbc.sql(
            "SELECT count(*) FROM ledger_discrepancies WHERE shipment_id = :s AND kind = :k")
        .param("s", shipment)
        .param("k", kind)
        .query(Long.class)
        .single();
  }

  // ---- AC-1, AC-2 -------------------------------------------------------------------------

  @Test
  @DisplayName("AC-1: a CANCELLED part creates a CANCELLED register row and writes no entry")
  void cancelledPartCreatesCancelledRowWithoutEntries() {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID sh = UUID.randomUUID();

    consumeOne(order, sh, s, "CANCELLED", "100.00");

    assertThat(regStatus(sh)).isEqualTo("CANCELLED");
    assertThat(allEntries(sh)).isZero();
    assertThat(saleLines(sh)).isZero();
  }

  @Test
  @DisplayName("AC-2: ORDER_CANCELLED marks its CANCELLED parts and does not force the others")
  void orderCancelledMarksOnlyCancelledParts_deliveredNotForced() {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID s1 = UUID.randomUUID();
    UUID s2 = UUID.randomUUID();
    UUID delivered = UUID.randomUUID();
    consumeOne(order, delivered, s, "DELIVERED", "100.00");

    consume(
        "ORDER_CANCELLED",
        order,
        "CANCELLED",
        List.of(
            part(s1, s, "CANCELLED", "50.00"),
            part(s2, s, "CANCELLED", "60.00"),
            part(delivered, s, "DELIVERED", "100.00")));

    assertThat(regStatus(s1)).isEqualTo("CANCELLED");
    assertThat(regStatus(s2)).isEqualTo("CANCELLED");
    assertThat(regStatus(delivered)).isEqualTo("DELIVERED");
    assertThat(entries(delivered, "SALE")).isEqualTo(1);
    assertThat(allEntries(s1) + allEntries(s2)).isZero();
  }

  // ---- AC-3 -------------------------------------------------------------------------------

  @Test
  @DisplayName("AC-3: DELIVERED after CANCELLED books nothing, stays CANCELLED, warns with ids only")
  void deliveredAfterCancelledBooksNothingAndWarns(CapturedOutput output) {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID sh = UUID.randomUUID();
    consumeOne(order, sh, s, "CANCELLED", "100.00");

    assertThatCode(() -> consumeOne(order, sh, s, "DELIVERED", "777.77")).doesNotThrowAnyException();

    assertThat(entries(sh, "SALE")).isZero();
    assertThat(entries(sh, "COMMISSION")).isZero();
    assertThat(entries(sh, "FEE")).isZero();
    assertThat(saleLines(sh)).isZero();
    assertThat(regStatus(sh)).isEqualTo("CANCELLED");
    assertThat(regAmount(sh)).isEqualByComparingTo("100.00"); // the forged amount is not stored

    List<String> warns =
        output.getAll().lines().filter(l -> l.contains("WARN") && l.contains(sh.toString())).toList();
    assertThat(warns).hasSize(1);
    String warn = warns.get(0); // positive anchor: the line is found by the shipment id
    assertThat(warn).contains(order.toString()).contains(sh.toString());
    assertThat(warn).doesNotContain("777.77").doesNotContain("Secret Widget");
  }

  // ---- AC-4 -------------------------------------------------------------------------------

  @Test
  @DisplayName("AC-4: CANCELLED after a booked DELIVERED leaves the entries exactly as they were")
  void cancelAfterDeliveredKeepsEntriesUnchanged() {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID sh = UUID.randomUUID();
    consumeOne(order, sh, s, "DELIVERED", "100.00");
    assertThat(allEntries(sh)).isEqualTo(3); // SALE, COMMISSION, FEE

    assertThatCode(() -> consumeOne(order, sh, s, "CANCELLED", "100.00")).doesNotThrowAnyException();

    assertThat(allEntries(sh)).isEqualTo(3);
    assertThat(entries(sh, "SALE")).isEqualTo(1);
    assertThat(entries(sh, "COMMISSION")).isEqualTo(1);
    assertThat(entries(sh, "FEE")).isEqualTo(1);
    assertThat(regStatus(sh)).isEqualTo("CANCELLED");
  }

  // ---- AC-5, AC-7 -------------------------------------------------------------------------

  @Test
  @DisplayName("AC-5: replays and earlier statuses leave a CANCELLED row untouched")
  void replayAndEarlierStatusKeepCancelledAndAmountAndUpdatedAt() {
    UUID s = seller();
    UUID other = seller();
    UUID order = UUID.randomUUID();
    UUID sh = UUID.randomUUID();
    consumeOne(order, sh, s, "CANCELLED", "100.00");
    age(sh);
    OffsetDateTime before = regUpdatedAt(sh);

    consumeOne(order, sh, other, "CANCELLED", "999.00"); // same-rank replay, other amount and seller
    for (String earlier : List.of("PLACED", "CONFIRMED", "PACKED", "SHIPPED", "OUT_FOR_DELIVERY")) {
      consumeOne(order, sh, other, earlier, "555.00");
    }
    consumeOne(order, sh, other, "NOT_A_STATUS", "555.00");

    assertThat(regStatus(sh)).isEqualTo("CANCELLED");
    assertThat(regAmount(sh)).isEqualByComparingTo("100.00");
    assertThat(regSeller(sh)).isEqualTo(s);
    assertThat(regUpdatedAt(sh)).isEqualTo(before);
  }

  @Test
  @DisplayName("AC-7: the same CANCELLED and ORDER_CANCELLED event twice leaves identical state")
  void duplicateCancelledEventsAreIdempotent() {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID s1 = UUID.randomUUID();
    UUID s2 = UUID.randomUUID();
    List<Part> parts = List.of(part(s1, s, "CANCELLED", "50.00"), part(s2, s, "CANCELLED", "60.00"));
    consume("ORDER_CANCELLED", order, "CANCELLED", parts);
    age(s1);
    age(s2);
    OffsetDateTime t1 = regUpdatedAt(s1);
    OffsetDateTime t2 = regUpdatedAt(s2);

    assertThatCode(() -> consume("ORDER_CANCELLED", order, "CANCELLED", parts))
        .doesNotThrowAnyException();
    assertThatCode(() -> consume("SHIPMENT_STATUS_CHANGED", order, "CANCELLED", parts))
        .doesNotThrowAnyException();

    assertThat(jdbc.sql("SELECT count(*) FROM ledger_shipments WHERE order_id = :o")
            .param("o", order)
            .query(Long.class)
            .single())
        .isEqualTo(2);
    assertThat(regUpdatedAt(s1)).isEqualTo(t1);
    assertThat(regUpdatedAt(s2)).isEqualTo(t2);
    assertThat(allEntries(s1) + allEntries(s2)).isZero();
  }

  // ---- AC-6 -------------------------------------------------------------------------------

  @Test
  @DisplayName("AC-6: every order of SHIPPED, DELIVERED, CANCELLED ends CANCELLED; books iff DELIVERED came first")
  void allPermutationsOfShippedDeliveredCancelled() {
    UUID s = seller();
    List<List<String>> perms =
        List.of(
            List.of("SHIPPED", "DELIVERED", "CANCELLED"),
            List.of("SHIPPED", "CANCELLED", "DELIVERED"),
            List.of("DELIVERED", "SHIPPED", "CANCELLED"),
            List.of("DELIVERED", "CANCELLED", "SHIPPED"),
            List.of("CANCELLED", "SHIPPED", "DELIVERED"),
            List.of("CANCELLED", "DELIVERED", "SHIPPED"));
    int booked = 0;
    for (List<String> perm : perms) {
      UUID order = UUID.randomUUID();
      UUID sh = UUID.randomUUID();
      perm.forEach(st -> consumeOne(order, sh, s, st, "100.00"));

      boolean deliveredFirst = perm.indexOf("DELIVERED") < perm.indexOf("CANCELLED");
      assertThat(regStatus(sh)).as(perm.toString()).isEqualTo("CANCELLED");
      assertThat(entries(sh, "SALE")).as(perm.toString()).isEqualTo(deliveredFirst ? 1 : 0);
      assertThat(entries(sh, "COMMISSION")).as(perm.toString()).isEqualTo(deliveredFirst ? 1 : 0);
      assertThat(entries(sh, "FEE")).as(perm.toString()).isEqualTo(deliveredFirst ? 1 : 0);
      booked += deliveredFirst ? 1 : 0;
    }
    assertThat(booked).isEqualTo(3); // positive control: the booking path ran for three orders
  }

  // ---- AC-8, AC-9 -------------------------------------------------------------------------

  @Test
  @DisplayName("AC-8: the reconcile reports nothing for a CANCELLED shipment without a SALE")
  void reconcileQuietForCancelledWithoutSale() {
    UUID s = seller();
    UUID up = UUID.randomUUID();
    UUID down = UUID.randomUUID();
    for (UUID sh : List.of(up, down)) {
      consumeOne(UUID.randomUUID(), sh, s, "CANCELLED", "100.00");
      age(sh);
    }
    reconciler.runNow().orElseThrow(); // order-service reachable

    when(orders.shipment(down)).thenThrow(new IllegalStateException("order-service down"));
    reconciler.runNow().orElseThrow(); // and unreachable

    for (UUID sh : List.of(up, down)) {
      assertThat(found(sh, "MISSING_SALE")).isZero();
      assertThat(found(sh, "STATE_MISMATCH")).isZero();
      assertThat(found(sh, "AMOUNT_MISMATCH")).isZero();
    }
    // positive control: the same run does report a DELIVERED shipment without a SALE
    UUID missing = UUID.randomUUID();
    ledger.track(UUID.randomUUID(), List.of(part(missing, s, "DELIVERED", "100.00")), "INR");
    age(missing);
    reconciler.runNow().orElseThrow();
    assertThat(found(missing, "MISSING_SALE")).isEqualTo(1);
  }

  @Test
  @DisplayName("AC-9: a CANCELLED shipment that still has a SALE stays a STATE_MISMATCH")
  void reconcileStillFlagsStateMismatchForCancelledWithSale() {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID sh = UUID.randomUUID();
    consumeOne(order, sh, s, "DELIVERED", "100.00");
    consumeOne(order, sh, s, "CANCELLED", "100.00");
    when(orders.shipment(sh))
        .thenReturn(new ShipmentInfo(sh, order, UUID.randomUUID(), s, "CANCELLED"));

    reconciler.runNow().orElseThrow();

    assertThat(found(sh, "STATE_MISMATCH")).isEqualTo(1);
    assertThat(entries(sh, "SALE")).isEqualTo(1);
  }

  // ---- AC-10 ------------------------------------------------------------------------------

  @Test
  @DisplayName("AC-10: a mixed event books only the DELIVERED part")
  void mixedEventBooksOnlyDeliveredAndCancelledWriteSurvivesPartError() {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID cancelled = UUID.randomUUID();
    UUID delivered = UUID.randomUUID();
    UUID transit = UUID.randomUUID();
    consumeOne(order, cancelled, s, "CANCELLED", "40.00");

    consume(
        "SHIPMENT_STATUS_CHANGED",
        order,
        "DELIVERED",
        List.of(
            part(cancelled, s, "DELIVERED", "40.00"),
            part(delivered, s, "DELIVERED", "100.00"),
            part(transit, s, "SHIPPED", "30.00")));

    assertThat(allEntries(cancelled)).isZero();
    assertThat(saleLines(cancelled)).isZero();
    assertThat(entries(delivered, "SALE")).isEqualTo(1);
    assertThat(allEntries(transit)).isZero();
    assertThat(regStatus(cancelled)).isEqualTo("CANCELLED");
    assertThat(regStatus(transit)).isEqualTo("SHIPPED");
  }

  @Test
  @DisplayName("AC-10: an error on a later part does not undo the CANCELLED register write")
  void cancelledRegisterWriteSurvivesAnErrorOnAnotherPart() {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID cancelled = UUID.randomUUID();
    UUID bad = UUID.randomUUID();
    Part broken =
        new Part(
            bad.toString(),
            s.toString(),
            "DELIVERED",
            new BigDecimal("10.00"),
            List.of(new Item("not-a-uuid", "x", 1, new BigDecimal("10.00"))));

    assertThatThrownBy(
            () ->
                consume(
                    "ORDER_CANCELLED",
                    order,
                    "CANCELLED",
                    List.of(part(cancelled, s, "CANCELLED", "40.00"), broken)))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(regStatus(cancelled)).isEqualTo("CANCELLED");
    assertThat(entries(bad, "SALE")).isZero();
  }

  // ---- AC-11 ------------------------------------------------------------------------------

  @Test
  @DisplayName("AC-11: repairing a MISSING_SALE of a cancelled shipment is a 409 and books nothing")
  void repairMissingSaleOnCancelledReturns409AndLeavesDiscrepancyOpen() {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID sh = UUID.randomUUID();
    ledger.track(order, List.of(part(sh, s, "DELIVERED", "100.00")), "INR");
    age(sh);
    reconciler.runNow().orElseThrow();
    UUID id =
        jdbc.sql("SELECT id FROM ledger_discrepancies WHERE shipment_id = :s AND kind = 'MISSING_SALE'")
            .param("s", sh)
            .query(UUID.class)
            .single();
    ledger.track(order, List.of(part(sh, s, "CANCELLED", "100.00")), "INR");
    // order-service still (stale) confirms DELIVERED, so the repair gets as far as booking
    when(orders.shipment(sh))
        .thenReturn(new ShipmentInfo(sh, order, UUID.randomUUID(), s, "DELIVERED"));

    assertThatThrownBy(() -> reconciler.repair(id, "fin-1", new BigDecimal("80.00")))
        .isInstanceOf(ResourceConflictException.class)
        .hasMessageContaining("shipment is cancelled");

    assertThat(allEntries(sh)).isZero();
    assertThat(
            jdbc.sql("SELECT resolved_at IS NULL FROM ledger_discrepancies WHERE id = :i")
                .param("i", id)
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  // ---- edge cases -------------------------------------------------------------------------

  @Test
  @DisplayName("legacy event without shipments: a CANCELLED order blocks a later DELIVERED")
  void legacyCancelledEventBlocksLaterDelivered() {
    UUID control = UUID.randomUUID();
    legacy(control, "DELIVERED");
    assertThat(entries(control, "SALE")).isEqualTo(1); // positive control: legacy delivery books

    UUID order = UUID.randomUUID();
    legacy(order, "CANCELLED");
    legacy(order, "DELIVERED");

    assertThat(entries(order, "SALE")).isZero();
    assertThat(regStatus(order)).isEqualTo("CANCELLED");
  }

  private void legacy(UUID order, String status) {
    listener.onEvent(
        new OrderEvt(
            "ORDER_STATUS_CHANGED",
            order.toString(),
            status,
            "PAID",
            new BigDecimal("100.00"),
            "INR",
            null,
            null,
            null,
            null,
            null,
            null));
  }

  @Test
  @DisplayName("parts with a null shipmentId or status are skipped without error")
  void nullShipmentOrStatusPartSkipped() {
    UUID s = seller();
    UUID order = UUID.randomUUID();

    assertThatCode(
            () ->
                consume(
                    "SHIPMENT_STATUS_CHANGED",
                    order,
                    "CANCELLED",
                    List.of(
                        new Part(null, s.toString(), "CANCELLED", BigDecimal.TEN, List.of()),
                        new Part(
                            UUID.randomUUID().toString(), s.toString(), null, BigDecimal.TEN, List.of()))))
        .doesNotThrowAnyException();

    assertThat(
            jdbc.sql("SELECT count(*) FROM ledger_shipments WHERE order_id = :o")
                .param("o", order)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  @DisplayName("a shipment that was never cancelled books exactly as before")
  void nonCancelledShipmentUnchanged() {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID sh = UUID.randomUUID();

    consumeOne(order, sh, s, "SHIPPED", "100.00");
    consumeOne(order, sh, s, "DELIVERED", "100.00");
    consumeOne(order, sh, s, "DELIVERED", "100.00");

    assertThat(allEntries(sh)).isEqualTo(3);
    assertThat(
            jdbc.sql("SELECT amount FROM ledger_entries WHERE shipment_id = :s AND type = :t")
                .param("s", sh)
                .param("t", "COMMISSION")
                .query(BigDecimal.class)
                .single())
        .isEqualByComparingTo("-10.00");
    assertThat(regStatus(sh)).isEqualTo("DELIVERED");
  }

  @Test
  @DisplayName("concurrent CANCELLED and DELIVERED never deadlock and end CANCELLED, booked whole or not at all")
  void concurrentCancelAndDeliverNeverBookAfterCancel() throws Exception {
    UUID s = seller();
    var pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 15; i++) {
        UUID order = UUID.randomUUID();
        UUID sh = UUID.randomUUID();
        consumeOne(order, sh, s, "SHIPPED", "100.00");
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (String st : List.of("CANCELLED", "DELIVERED")) {
          fs.add(
              pool.submit(
                  () -> {
                    go.await();
                    consumeOne(order, sh, s, st, "100.00");
                    return null;
                  }));
        }
        go.countDown();
        for (Future<?> f : fs) {
          f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }

        assertThat(regStatus(sh)).isEqualTo("CANCELLED");
        long sale = entries(sh, "SALE");
        assertThat(sale).isBetween(0L, 1L);
        assertThat(entries(sh, "COMMISSION")).isEqualTo(sale);
        assertThat(entries(sh, "FEE")).isEqualTo(sale);
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
