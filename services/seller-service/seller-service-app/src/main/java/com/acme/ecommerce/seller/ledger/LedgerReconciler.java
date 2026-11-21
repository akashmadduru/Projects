package com.acme.ecommerce.seller.ledger;

import com.acme.ecommerce.common.core.error.ApplicationException;
import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.core.error.ResourceConflictException;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.web.audit.AuditPublisher;
import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.seller.client.OrderFeignClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Read-only check of the ledger against the shipment register (reports to ledger_discrepancies,
 * never posts; Q2), plus the admin repair of reported rows. Each page is a short read without row
 * locks, so it never waits on a payout run.
 */
@Service
public class LedgerReconciler {

  private static final Logger log = LoggerFactory.getLogger(LedgerReconciler.class);
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");
  private static final String JOB = "seller-ledger-reconcile";
  private static final List<String> KINDS =
      List.of("MISSING_SALE", "AMOUNT_MISMATCH", "STATE_MISMATCH", "UNMATCHED_REVERSAL");

  public record Discrepancy(
      UUID id,
      UUID shipmentId,
      UUID orderId,
      UUID sellerId,
      String kind,
      UUID ref,
      BigDecimal expected,
      BigDecimal actual,
      String detail,
      OffsetDateTime firstSeenAt,
      OffsetDateTime lastSeenAt,
      OffsetDateTime resolvedAt,
      String resolvedBy) {}

  private record Row(
      UUID shipmentId,
      UUID orderId,
      UUID sellerId,
      String status,
      BigDecimal amount,
      boolean pastGrace,
      BigDecimal saleAmount,
      boolean recentSale) {}

  private final JdbcClient jdbc;
  private final JobLock jobLock;
  private final OrderFeignClient orders;
  private final LedgerService ledger;
  private final Duration grace;
  private final int lookbackDays;
  private final int pageSize;
  private final int maxFeignErrors;
  private final TransactionTemplate tx;
  private final ObjectProvider<AuditPublisher> audit;

  public LedgerReconciler(
      JdbcClient jdbc,
      JobLock jobLock,
      OrderFeignClient orders,
      LedgerService ledger,
      @Value("${seller.ledger.reconcile.grace:PT1H}") Duration grace,
      @Value("${seller.ledger.reconcile.lookback-days:35}") int lookbackDays,
      @Value("${seller.ledger.reconcile.page-size:200}") int pageSize,
      @Value("${seller.ledger.reconcile.max-feign-errors:5}") int maxFeignErrors,
      PlatformTransactionManager txManager,
      ObjectProvider<AuditPublisher> audit) {
    this.jdbc = jdbc;
    this.jobLock = jobLock;
    this.orders = orders;
    this.ledger = ledger;
    this.grace = grace;
    this.lookbackDays = lookbackDays;
    this.pageSize = pageSize;
    this.maxFeignErrors = maxFeignErrors;
    this.tx = new TransactionTemplate(txManager);
    this.audit = audit;
  }

  @Scheduled(cron = "${seller.ledger.reconcile.cron}", zone = "UTC")
  public void scheduled() {
    runNow();
  }

  /** Findings per kind (plus FEIGN_ERRORS), or empty when another run holds the lock. */
  public Optional<Map<String, Integer>> runNow() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    KINDS.forEach(k -> counts.put(k, 0));
    counts.put("FEIGN_ERRORS", 0);
    boolean ran = jobLock.runExclusive(JOB, () -> reconcile(counts));
    return ran ? Optional.of(counts) : Optional.empty();
  }

  private void reconcile(Map<String, Integer> counts) {
    UUID after = new UUID(0, 0);
    int[] streak = {0};
    while (true) {
      List<Row> page =
          jdbc.sql(
                  "SELECT r.shipment_id, r.order_id, r.seller_id, r.status, r.amount,"
                      + " r.updated_at < now() - CAST(:g AS interval), s.amount,"
                      + " s.created_at >= now() - make_interval(days => :d)"
                      + " FROM ledger_shipments r LEFT JOIN ledger_entries s"
                      + " ON s.shipment_id = r.shipment_id AND s.type = 'SALE' AND s.return_id IS NULL"
                      + " WHERE r.shipment_id > :after ORDER BY r.shipment_id LIMIT :n")
              .param("g", grace.toSeconds() + " seconds")
              .param("d", lookbackDays)
              .param("after", after)
              .param("n", pageSize)
              .query(
                  (rs, n) ->
                      new Row(
                          rs.getObject(1, UUID.class),
                          rs.getObject(2, UUID.class),
                          rs.getObject(3, UUID.class),
                          rs.getString(4),
                          rs.getBigDecimal(5),
                          rs.getBoolean(6),
                          rs.getBigDecimal(7),
                          rs.getBoolean(8)))
              .list();
      page.forEach(r -> check(r, counts, streak));
      if (page.size() < pageSize) {
        return;
      }
      after = page.get(page.size() - 1).shipmentId();
    }
  }

  private void check(Row r, Map<String, Integer> counts, int[] streak) {
    if (r.saleAmount() == null) {
      if ("DELIVERED".equals(r.status()) && r.pastGrace()) {
        report(counts, r, "MISSING_SALE", r.amount(), BigDecimal.ZERO, "delivered, no SALE booked");
      }
      return;
    }
    if (r.amount().compareTo(r.saleAmount()) != 0) {
      report(
          counts, r, "AMOUNT_MISMATCH", r.amount(), r.saleAmount(), "SALE differs from register");
    }
    boolean legacy = r.shipmentId().equals(r.orderId());
    if (r.recentSale() && !legacy && streak[0] < maxFeignErrors) {
      try {
        var ship = orders.shipment(r.shipmentId());
        streak[0] = 0;
        if (!"DELIVERED".equals(ship.status()) || !r.sellerId().equals(ship.sellerId())) {
          report(counts, r, "STATE_MISMATCH", null, null, "order-service: " + ship.status());
        }
      } catch (feign.FeignException.NotFound e) {
        streak[0] = 0;
        report(counts, r, "STATE_MISMATCH", null, null, "order-service: shipment not found");
      } catch (RuntimeException e) {
        counts.merge("FEIGN_ERRORS", 1, Integer::sum);
        if (++streak[0] >= maxFeignErrors) {
          counts.put("FEIGN_STOPPED", 1);
          log.warn("Reconcile stops asking order-service after {} errors in a row", streak[0]);
        }
        log.warn(
            "Reconcile could not ask order-service about {}: {}", r.shipmentId(), e.toString());
      }
    }
  }

  private void report(
      Map<String, Integer> counts,
      Row r,
      String kind,
      BigDecimal expected,
      BigDecimal actual,
      String detail) {
    jdbc.sql(
            "INSERT INTO ledger_discrepancies (shipment_id, order_id, seller_id, kind, expected, actual, detail)"
                + " VALUES (:sh, :o, :s, :k, :e, :a, :d) ON CONFLICT (shipment_id, kind, ref)"
                + " DO UPDATE SET expected = EXCLUDED.expected, actual = EXCLUDED.actual,"
                + " last_seen_at = now()")
        .param("sh", r.shipmentId())
        .param("o", r.orderId())
        .param("s", r.sellerId())
        .param("k", kind)
        .param("e", expected)
        .param("a", actual)
        .param("d", detail)
        .update();
    counts.merge(kind, 1, Integer::sum);
    log.warn("LEDGER DISCREPANCY {} shipment={} detail={}", kind, r.shipmentId(), detail);
  }

  // ---- admin ------------------------------------------------------------------------------

  /** Newest first, capped at 200; the open rows unless {@code open} is false. */
  public List<Discrepancy> list(boolean open) {
    return jdbc.sql(
            "SELECT * FROM ledger_discrepancies"
                + (open ? " WHERE resolved_at IS NULL" : "")
                + " ORDER BY first_seen_at DESC, id LIMIT 200")
        .query(LedgerReconciler::map)
        .list();
  }

  private static Discrepancy map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new Discrepancy(
        rs.getObject("id", UUID.class),
        rs.getObject("shipment_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getObject("seller_id", UUID.class),
        rs.getString("kind"),
        rs.getObject("ref", UUID.class),
        rs.getBigDecimal("expected"),
        rs.getBigDecimal("actual"),
        rs.getString("detail"),
        rs.getObject("first_seen_at", OffsetDateTime.class),
        rs.getObject("last_seen_at", OffsetDateTime.class),
        rs.getObject("resolved_at", OffsetDateTime.class),
        rs.getString("resolved_by"));
  }

  /**
   * Posts the missing booking through the normal idempotent paths; {@code actor} is the caller.
   * order-service confirms status and seller but not the amount, so a MISSING_SALE repair books
   * only the admin-entered {@code amount}, rounded to cents and never above the register amount of
   * the row (a larger one is a manual DB procedure, see the runbook). The row is locked while it is
   * booked, so two admins cannot both book.
   */
  public Discrepancy repair(UUID id, String actor, BigDecimal amount) {
    Discrepancy d = find(id);
    if (d.resolvedAt() != null) {
      return d;
    }
    String[] outcome = new String[2];
    try {
      BigDecimal cents = precheck(d, amount);
      tx.executeWithoutResult(
          st -> {
            Optional<UUID> locked =
                jdbc.sql(
                        "SELECT id FROM ledger_discrepancies WHERE id = :i AND resolved_at IS NULL FOR UPDATE")
                    .param("i", id)
                    .query(UUID.class)
                    .optional();
            if (locked.isEmpty()) {
              return;
            }
            outcome[0] = book(d, cents);
            jdbc.sql(
                    "UPDATE ledger_discrepancies SET resolved_at = now(), resolved_by = :a,"
                        + " detail = COALESCE(detail, '') || CAST(:n AS text) WHERE id = :i")
                .param("a", actor)
                .param("n", outcome[0])
                .param("i", id)
                .update();
          });
      outcome[1] = cents == null ? null : cents.toPlainString();
    } catch (ApplicationException e) {
      log.warn("Ledger repair of {} ({}) refused for {}: {}", id, d.kind(), actor, e.getMessage());
      throw e;
    }
    if (outcome[0] != null) {
      log.info("Ledger repair of {} ({}) done by {}{}", id, d.kind(), actor, outcome[0]);
      AuditPublisher publisher = audit.getIfAvailable();
      if (publisher != null) {
        publisher.publish(
            "REPAIR_LEDGER_DISCREPANCY",
            "LEDGER_DISCREPANCY",
            id.toString(),
            null,
            "{\"kind\":\"" + d.kind() + "\",\"amount\":" + (outcome[1] == null ? "null" : outcome[1])
                + ",\"registerAmount\":" + d.expected() + ",\"note\":\""
                + outcome[0].replace("\"", "'") + "\"}",
            null);
      }
    }
    return find(id);
  }

  private Discrepancy find(UUID id) {
    return jdbc.sql("SELECT * FROM ledger_discrepancies WHERE id = :i")
        .param("i", id)
        .query(LedgerReconciler::map)
        .optional()
        .orElseThrow(() -> new ResourceNotFoundException("Discrepancy", id));
  }

  /** Checks that need no lock: the amount, then order-service. Returns the amount in cents. */
  private BigDecimal precheck(Discrepancy d, BigDecimal amount) {
    if (!"MISSING_SALE".equals(d.kind())) {
      return null;
    }
    if (amount == null) {
      throw new ResourceConflictException("Enter the shipment amount (amount=...) to repair");
    }
    BigDecimal cents = amount.setScale(2, RoundingMode.HALF_UP);
    if (cents.signum() <= 0 || cents.compareTo(MAX_AMOUNT) >= 0) {
      throw new ResourceConflictException("Amount must be above 0.00 and below 10^12");
    }
    if (d.expected() != null && cents.compareTo(d.expected()) > 0) {
      throw new ResourceConflictException("Amount is above the register amount " + d.expected());
    }
    OrderFeignClient.ShipmentInfo ship;
    try {
      ship = orders.shipment(d.shipmentId());
    } catch (feign.FeignException.NotFound e) {
      throw new ResourceConflictException("Shipment is unknown to order-service");
    } catch (RuntimeException e) {
      throw new CodedException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Order lookup unavailable", e);
    }
    if (!"DELIVERED".equals(ship.status()) || !d.sellerId().equals(ship.sellerId())) {
      throw new ResourceConflictException("Order-service does not confirm a delivery");
    }
    return cents;
  }

  /** Posts inside the row lock; the returned text goes to detail and the log. */
  private String book(Discrepancy d, BigDecimal cents) {
    switch (d.kind()) {
      case "MISSING_SALE" -> {
        BigDecimal present = saleAmount(d.shipmentId());
        if (present != null) {
          return "; SALE already present (amount " + present + ")";
        }
        String currency =
            jdbc.sql("SELECT currency FROM ledger_shipments WHERE shipment_id = :s")
                .param("s", d.shipmentId())
                .query(String.class)
                .single();
        ledger.recordDelivered(
            d.orderId(),
            new LedgerService.Part(
                d.shipmentId().toString(), d.sellerId().toString(), "DELIVERED", cents, null),
            currency);
        BigDecimal booked = saleAmount(d.shipmentId());
        if (booked == null) {
          throw new ResourceConflictException("SALE could not be booked (unknown seller?)");
        }
        return booked.compareTo(cents) == 0
            ? "; repaired with admin-entered amount " + booked
            : "; SALE already present (amount " + booked + ")";
      }
      case "UNMATCHED_REVERSAL" -> {
        if (saleAmount(d.shipmentId()) == null) {
          throw new ResourceConflictException("No SALE booked yet; repair the delivery first");
        }
        try {
          ledger.reverseReturn(d.orderId(), d.shipmentId(), d.ref(), d.expected(), "INR");
        } catch (IllegalArgumentException e) {
          throw new ResourceConflictException("Reversal names another order than the SALE");
        }
        return "";
      }
      default -> throw new ResourceConflictException(d.kind() + " needs manual correction");
    }
  }

  private BigDecimal saleAmount(UUID shipmentId) {
    return jdbc.sql(
            "SELECT amount FROM ledger_entries WHERE shipment_id = :s AND type = 'SALE'"
                + " AND return_id IS NULL")
        .param("s", shipmentId)
        .query(BigDecimal.class)
        .optional()
        .orElse(null);
  }
}
