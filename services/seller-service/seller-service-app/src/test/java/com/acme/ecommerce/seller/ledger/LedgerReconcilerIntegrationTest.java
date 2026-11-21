package com.acme.ecommerce.seller.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.seller.client.OrderFeignClient;
import com.acme.ecommerce.seller.client.OrderFeignClient.ShipmentInfo;
import com.acme.ecommerce.seller.support.AbstractSellerIntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconcile job, discrepancy report and admin repair (F-20261119-13, AC-7, AC-8). */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LedgerReconcilerIntegrationTest extends AbstractSellerIntegrationTest {

  @Autowired private LedgerService ledger;
  @Autowired private LedgerReconciler reconciler;
  @Autowired private JdbcClient jdbc;
  @Autowired private JobLock jobLock;
  @Autowired private MockMvc mvc;
  @Autowired private PlatformTransactionManager txManager;
  @MockitoBean private OrderFeignClient orders;

  private UUID seller() {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO sellers (id, user_id, display_name, slug, status, commission_percent)"
                + " VALUES (:i, :u, 'Rec Seller', :s, 'APPROVED', 10)")
        .param("i", id)
        .param("u", UUID.randomUUID())
        .param("s", "rec-" + id)
        .update();
    return id;
  }

  private LedgerService.Part part(UUID shipment, UUID seller, String status, String amount) {
    return new LedgerService.Part(
        shipment.toString(), seller.toString(), status, new BigDecimal(amount), List.of());
  }

  /** A registered shipment, optionally with its SALE booked. */
  private void shipment(UUID order, UUID shipment, UUID seller, String status, boolean sale) {
    ledger.track(order, List.of(part(shipment, seller, status, "100.00")), "INR");
    if (sale) {
      ledger.recordDelivered(order, part(shipment, seller, "DELIVERED", "100.00"), "INR");
    }
  }

  private void age(UUID shipment) {
    jdbc.sql(
            "UPDATE ledger_shipments SET updated_at = now() - interval '2 hours' WHERE shipment_id = :s")
        .param("s", shipment)
        .update();
  }

  private void feignSays(UUID shipment, UUID order, UUID seller, String status) {
    when(orders.shipment(shipment))
        .thenReturn(new ShipmentInfo(shipment, order, UUID.randomUUID(), seller, status));
  }

  private long found(UUID shipment, String kind) {
    return jdbc.sql(
            "SELECT count(*) FROM ledger_discrepancies WHERE shipment_id = :s AND kind = :k")
        .param("s", shipment)
        .param("k", kind)
        .query(Long.class)
        .single();
  }

  private long entries(UUID shipment, String type) {
    return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE shipment_id = :s AND type = :t")
        .param("s", shipment)
        .param("t", type)
        .query(Long.class)
        .single();
  }

  private UUID discrepancyId(UUID shipment, String kind) {
    return jdbc.sql("SELECT id FROM ledger_discrepancies WHERE shipment_id = :s AND kind = :k")
        .param("s", shipment)
        .param("k", kind)
        .query(UUID.class)
        .single();
  }

  private static feign.FeignException notFound() {
    return new feign.FeignException.NotFound(
        "gone",
        feign.Request.create(
            feign.Request.HttpMethod.GET,
            "/x",
            new HashMap<>(),
            null,
            StandardCharsets.UTF_8,
            null),
        null,
        null);
  }

  // ---- reconcile --------------------------------------------------------------------------

  @Test
  @DisplayName("a DELIVERED shipment without a SALE is reported after the grace period only")
  void missingSaleReported() {
    UUID s = seller();
    UUID aged = UUID.randomUUID();
    UUID fresh = UUID.randomUUID();
    shipment(UUID.randomUUID(), aged, s, "DELIVERED", false);
    shipment(UUID.randomUUID(), fresh, s, "DELIVERED", false);
    age(aged);

    var counts = reconciler.runNow().orElseThrow();

    assertThat(found(aged, "MISSING_SALE")).isEqualTo(1);
    assertThat(found(fresh, "MISSING_SALE")).isZero(); // inside the grace period
    assertThat(counts.get("MISSING_SALE")).isGreaterThanOrEqualTo(1);
    assertThat(
            jdbc.sql(
                    "SELECT expected FROM ledger_discrepancies WHERE shipment_id = :s AND kind = 'MISSING_SALE'")
                .param("s", aged)
                .query(BigDecimal.class)
                .single())
        .isEqualByComparingTo("100.00");
  }

  @Test
  @DisplayName("a DELIVERED event whose posting failed still leaves a register row to report")
  void missingSaleReportedAfterFailedPostingLeavesRegisterRow() {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    LedgerService.Part bad =
        new LedgerService.Part(
            shipment.toString(),
            s.toString(),
            "DELIVERED",
            new BigDecimal("100.00"),
            List.of(new LedgerService.Item("not-a-uuid", "x", 1, new BigDecimal("100.00"))));
    // the consumer's listener call: track, then post; the posting throws and rolls back
    assertThatThrownBy(
            () ->
                new TransactionTemplate(txManager)
                    .executeWithoutResult(
                        st -> {
                          ledger.track(order, List.of(bad), "INR");
                          ledger.recordDelivered(order, bad, "INR");
                        }))
        .isInstanceOf(IllegalArgumentException.class);
    age(shipment);

    reconciler.runNow().orElseThrow();

    assertThat(entries(shipment, "SALE")).isZero();
    assertThat(found(shipment, "MISSING_SALE")).isEqualTo(1);
  }

  @Test
  @DisplayName("a SALE that differs from the register amount is reported")
  void amountMismatchReported() {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    UUID matching = UUID.randomUUID();
    shipment(order, shipment, s, "DELIVERED", true);
    shipment(order, matching, s, "DELIVERED", true);
    ledger.track(order, List.of(part(shipment, s, "DELIVERED", "120.00")), "INR");

    reconciler.runNow().orElseThrow();

    assertThat(found(shipment, "AMOUNT_MISMATCH")).isEqualTo(1);
    assertThat(found(matching, "AMOUNT_MISMATCH")).isZero();
  }

  @Test
  @DisplayName("order-service saying not found, not delivered or another seller is reported")
  void stateMismatchReported() {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID gone = UUID.randomUUID();
    UUID cancelled = UUID.randomUUID();
    UUID otherSeller = UUID.randomUUID();
    UUID fine = UUID.randomUUID();
    for (UUID sh : List.of(gone, cancelled, otherSeller, fine)) {
      shipment(order, sh, s, "DELIVERED", true);
    }
    when(orders.shipment(gone)).thenThrow(notFound());
    feignSays(cancelled, order, s, "CANCELLED");
    feignSays(otherSeller, order, UUID.randomUUID(), "DELIVERED");
    feignSays(fine, order, s, "DELIVERED");

    reconciler.runNow().orElseThrow();

    assertThat(found(gone, "STATE_MISMATCH")).isEqualTo(1);
    assertThat(found(cancelled, "STATE_MISMATCH")).isEqualTo(1);
    assertThat(found(otherSeller, "STATE_MISMATCH")).isEqualTo(1);
    assertThat(found(fine, "STATE_MISMATCH")).isZero();
  }

  @Test
  @DisplayName("a Feign failure is counted and warned, never reported")
  void feignErrorIsCountedNotReported(CapturedOutput output) {
    UUID s = seller();
    UUID shipment = UUID.randomUUID();
    shipment(UUID.randomUUID(), shipment, s, "DELIVERED", true);
    when(orders.shipment(shipment)).thenThrow(new IllegalStateException("order-service down"));

    var counts = reconciler.runNow().orElseThrow();

    assertThat(found(shipment, "STATE_MISMATCH")).isZero();
    assertThat(counts.get("FEIGN_ERRORS")).isGreaterThanOrEqualTo(1);
    assertThat(output).contains("Reconcile could not ask order-service about " + shipment);
  }

  @Test
  @DisplayName("a second run updates the report and never duplicates it")
  void secondRunDoesNotDuplicate() {
    UUID s = seller();
    UUID shipment = UUID.randomUUID();
    shipment(UUID.randomUUID(), shipment, s, "DELIVERED", false);
    age(shipment);

    reconciler.runNow().orElseThrow();
    reconciler.runNow().orElseThrow();

    assertThat(found(shipment, "MISSING_SALE")).isEqualTo(1);
  }

  @Test
  @DisplayName("a run is skipped while another session holds the reconcile lock")
  void runSkippedWhenLockHeld() {
    boolean ranOuter =
        jobLock.runExclusive(
            "seller-ledger-reconcile", () -> assertThat(reconciler.runNow()).isEmpty());
    assertThat(ranOuter).isTrue();
    assertThat(reconciler.runNow()).isPresent(); // free again
  }

  @Test
  @DisplayName("reconcile writes no ledger rows")
  void reconcileWritesNoLedgerRows() {
    UUID s = seller();
    UUID shipment = UUID.randomUUID();
    shipment(UUID.randomUUID(), shipment, s, "DELIVERED", false);
    age(shipment);
    long before = jdbc.sql("SELECT count(*) FROM ledger_entries").query(Long.class).single();

    var counts = reconciler.runNow().orElseThrow();

    assertThat(counts.get("MISSING_SALE")).isGreaterThanOrEqualTo(1); // it did find something
    assertThat(jdbc.sql("SELECT count(*) FROM ledger_entries").query(Long.class).single())
        .isEqualTo(before);
  }

  // ---- admin API --------------------------------------------------------------------------

  private static RequestPostProcessor as(String subject, String role) {
    return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority(role));
  }

  private UUID missingSale(UUID s, UUID order, UUID shipment) {
    shipment(order, shipment, s, "DELIVERED", false);
    age(shipment);
    reconciler.runNow().orElseThrow();
    return discrepancyId(shipment, "MISSING_SALE");
  }

  private String resolvedBy(UUID id) {
    return jdbc.sql("SELECT resolved_by FROM ledger_discrepancies WHERE id = :i")
        .param("i", id)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  @Test
  @DisplayName("repairing a missing SALE twice posts SALE, COMMISSION and FEE once")
  void repairPostsMissingSaleOnce() throws Exception {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    UUID id = missingSale(s, order, shipment);
    feignSays(shipment, order, s, "DELIVERED");

    for (int i = 0; i < 2; i++) {
      mvc.perform(
              post("/api/admin/ledger/discrepancies/{id}/repair", id)
                  .param("amount", "80.00")
                  .with(as("fin-1", "ROLE_FINANCE")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.resolvedBy").value("fin-1"));
    }

    assertThat(entries(shipment, "SALE")).isEqualTo(1);
    assertThat(entries(shipment, "COMMISSION")).isEqualTo(1);
    assertThat(entries(shipment, "FEE")).isEqualTo(1);
    assertThat(resolvedBy(id)).isEqualTo("fin-1");
    // the admin-entered amount is booked, not the register's 100.00
    assertThat(
            jdbc.sql("SELECT amount FROM ledger_entries WHERE shipment_id = :s AND type = 'SALE'")
                .param("s", shipment)
                .query(BigDecimal.class)
                .single())
        .isEqualByComparingTo("80.00");
  }

  @Test
  @DisplayName("a missing-SALE repair without an admin-entered amount is a 409 and books nothing")
  void repairWithoutAmountIsRefused() throws Exception {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    UUID id = missingSale(s, order, shipment);
    feignSays(shipment, order, s, "DELIVERED");

    mvc.perform(
            post("/api/admin/ledger/discrepancies/{id}/repair", id)
                .with(as("fin-1", "ROLE_FINANCE")))
        .andExpect(status().isConflict());

    assertThat(entries(shipment, "SALE")).isZero();
    assertThat(resolvedBy(id)).isNull();
  }

  @Test
  @DisplayName("a repair is refused with 409 when order-service does not confirm the delivery")
  void repairRefusedWhenNotDelivered() throws Exception {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    UUID id = missingSale(s, order, shipment);
    feignSays(shipment, order, s, "CANCELLED");

    mvc.perform(
            post("/api/admin/ledger/discrepancies/{id}/repair", id)
                .with(as("fin-1", "ROLE_FINANCE")))
        .andExpect(status().isConflict());

    assertThat(entries(shipment, "SALE")).isZero();
    assertThat(resolvedBy(id)).isNull();
  }

  @Test
  @DisplayName("a repair answers 503 when order-service cannot be asked")
  void repairReturns503WhenFeignDown() throws Exception {
    UUID s = seller();
    UUID shipment = UUID.randomUUID();
    UUID id = missingSale(s, UUID.randomUUID(), shipment);
    when(orders.shipment(any())).thenThrow(new IllegalStateException("down"));

    mvc.perform(
            post("/api/admin/ledger/discrepancies/{id}/repair", id)
                .with(as("fin-1", "ROLE_FINANCE")))
        .andExpect(status().isServiceUnavailable());

    assertThat(entries(shipment, "SALE")).isZero();
  }

  @Test
  @DisplayName("an unmatched reversal is applied once its SALE exists")
  void repairUnmatchedReversalAfterSale() throws Exception {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    UUID ret = UUID.randomUUID();
    ledger.reverseReturn(order, shipment, ret, new BigDecimal("40.00"), "INR");
    UUID id = discrepancyId(shipment, "UNMATCHED_REVERSAL");
    ledger.recordDelivered(order, part(shipment, s, "DELIVERED", "100.00"), "INR");

    for (int i = 0; i < 2; i++) {
      mvc.perform(
              post("/api/admin/ledger/discrepancies/{id}/repair", id)
                  .with(as("adm-1", "ROLE_ADMIN")))
          .andExpect(status().isOk());
    }

    assertThat(entries(shipment, "REFUND_REVERSAL")).isEqualTo(1);
    assertThat(
            jdbc.sql(
                    "SELECT amount FROM ledger_entries WHERE shipment_id = :s AND type = 'REFUND_REVERSAL'")
                .param("s", shipment)
                .query(BigDecimal.class)
                .single())
        .isEqualByComparingTo("-40.00");
    assertThat(resolvedBy(id)).isEqualTo("adm-1");
  }

  @Test
  @DisplayName("an unmatched reversal without a SALE is a 409")
  void repairUnmatchedReversalWithoutSaleIs409() throws Exception {
    UUID shipment = UUID.randomUUID();
    ledger.reverseReturn(
        UUID.randomUUID(), shipment, UUID.randomUUID(), new BigDecimal("40.00"), "INR");
    UUID id = discrepancyId(shipment, "UNMATCHED_REVERSAL");

    mvc.perform(
            post("/api/admin/ledger/discrepancies/{id}/repair", id).with(as("adm-1", "ROLE_ADMIN")))
        .andExpect(status().isConflict());

    assertThat(resolvedBy(id)).isNull();
  }

  @Test
  @DisplayName("resolved_by is the authenticated principal, whatever the request body says")
  void resolvedByComesFromPrincipalNotBody() throws Exception {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    UUID id = missingSale(s, order, shipment);
    feignSays(shipment, order, s, "DELIVERED");

    mvc.perform(
            post("/api/admin/ledger/discrepancies/{id}/repair", id)
                .param("amount", "100.00")
                .with(as("principal-7", "ROLE_ADMIN"))
                .contentType("application/json")
                .content("{\"resolvedBy\":\"mallory\",\"resolved_by\":\"mallory\"}"))
        .andExpect(status().isOk());

    assertThat(resolvedBy(id)).isEqualTo("principal-7");
  }

  @Test
  @DisplayName("a refused repair is logged at WARN with the actor; a good one at INFO")
  void failedRepairLogsWarnWithActor(CapturedOutput output) throws Exception {
    UUID s = seller();
    UUID order = UUID.randomUUID();
    UUID shipment = UUID.randomUUID();
    UUID id = missingSale(s, order, shipment);
    feignSays(shipment, order, s, "CANCELLED");

    mvc.perform(
            post("/api/admin/ledger/discrepancies/{id}/repair", id)
                .with(as("fin-9", "ROLE_FINANCE")))
        .andExpect(status().isConflict());
    assertThat(output).containsPattern("WARN.*refused for fin-9");

    feignSays(shipment, order, s, "DELIVERED");
    mvc.perform(
            post("/api/admin/ledger/discrepancies/{id}/repair", id)
                .param("amount", "100.00")
                .with(as("fin-9", "ROLE_FINANCE")))
        .andExpect(status().isOk());
    assertThat(output)
        .containsPattern("INFO.*done by fin-9"); // positive control for the WARN match
  }

  @Test
  @DisplayName("a role other than ADMIN or FINANCE gets 403 on all three endpoints")
  void nonAdminFinanceRoleGets403() throws Exception {
    UUID id = UUID.randomUUID();
    RequestPostProcessor seller = as("seller-1", "ROLE_SELLER");
    mvc.perform(get("/api/admin/ledger/discrepancies").with(seller))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/admin/ledger/reconcile").with(seller)).andExpect(status().isForbidden());
    mvc.perform(post("/api/admin/ledger/discrepancies/{id}/repair", id).with(seller))
        .andExpect(status().isForbidden());
    // positive control: an allowed role passes the same gate
    mvc.perform(get("/api/admin/ledger/discrepancies").with(as("fin-1", "ROLE_FINANCE")))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("the reconcile endpoint answers 409 while the lock is held and counts otherwise")
  void reconcileEndpointReturns409WhenLockHeld() throws Exception {
    jobLock.runExclusive(
        "seller-ledger-reconcile",
        () -> {
          try {
            mvc.perform(post("/api/admin/ledger/reconcile").with(as("fin-1", "ROLE_FINANCE")))
                .andExpect(status().isConflict());
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
    mvc.perform(post("/api/admin/ledger/reconcile").with(as("fin-1", "ROLE_FINANCE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.MISSING_SALE").isNumber());
  }
}
