package com.acme.ecommerce.seller.ledger;

import com.acme.ecommerce.seller.Paged;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Settlement ledger (from order.events) and payout runs. */
@Service
public class LedgerService {

  private static final Logger log = LoggerFactory.getLogger(LedgerService.class);
  private static final BigDecimal HUNDRED = new BigDecimal("100");

  public record Item(String productId, String name, Integer quantity, BigDecimal amount) {}

  public record Part(
      String shipmentId, String sellerId, String status, BigDecimal amount, List<Item> items) {}

  public record LedgerEntry(
      UUID id,
      String type,
      BigDecimal amount,
      String currency,
      UUID orderId,
      UUID shipmentId,
      OffsetDateTime createdAt,
      UUID payoutId) {}

  public record Payout(
      UUID id,
      UUID sellerId,
      String sellerName,
      BigDecimal amount,
      String currency,
      String status,
      String reference,
      OffsetDateTime createdAt) {}

  public record PayoutRun(int count, List<Payout> payouts) {}

  private final JdbcClient jdbc;
  private final int defaultMinAgeDays;
  private final UUID platformSeller;

  public LedgerService(
      JdbcClient jdbc,
      @Value("${seller.payout-min-age-days:7}") int defaultMinAgeDays,
      @Value("${seller.platform-seller-id}") String platformSeller) {
    this.jdbc = jdbc;
    this.defaultMinAgeDays = defaultMinAgeDays;
    this.platformSeller = UUID.fromString(platformSeller);
  }

  // ---- event-driven postings --------------------------------------------------------------

  /** Fulfilment order of order-service statuses; the register never moves to a lower rank. */
  private static final String STATUS_RANK =
      "ARRAY['PENDING_PAYMENT','PLACED','CONFIRMED','PACKED','SHIPPED','OUT_FOR_DELIVERY','DELIVERED','CANCELLED']";

  /**
   * Records what order-service announced for each shipment in {@code ledger_shipments}, the source
   * the reconcile job compares the ledger against. It commits in its own transaction so a failing
   * posting (or an outer rollback) never removes it, and it is monotonic: an older event (lower
   * status rank) is ignored, so an out-of-order SHIPPED never overwrites DELIVERED.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void track(UUID orderId, List<Part> parts, String currency) {
    for (Part p : parts) {
      if (p.shipmentId() == null || p.status() == null) {
        continue;
      }
      jdbc.sql(
              "INSERT INTO ledger_shipments (shipment_id, order_id, seller_id, status, amount, currency)"
                  + " VALUES (:sh, :o, :s, :st, COALESCE(CAST(:a AS numeric), 0), :c)"
                  + " ON CONFLICT (shipment_id) DO UPDATE SET status = EXCLUDED.status,"
                  + " amount = COALESCE(CAST(:a AS numeric), ledger_shipments.amount), updated_at = now()"
                  + " WHERE COALESCE(array_position("
                  + STATUS_RANK
                  + ", EXCLUDED.status), 0) >= COALESCE(array_position("
                  + STATUS_RANK
                  + ", ledger_shipments.status), 0)")
          .param("sh", UUID.fromString(p.shipmentId()))
          .param("o", orderId)
          .param("s", p.sellerId() == null ? platformSeller : UUID.fromString(p.sellerId()))
          .param("st", p.status())
          .param("a", p.amount() == null ? null : p.amount().setScale(2, RoundingMode.HALF_UP))
          .param("c", currency.toUpperCase())
          .update();
    }
  }

  /** SALE + COMMISSION + FEE for a delivered shipment. Idempotent on (shipment, type). */
  @Transactional
  public void recordDelivered(UUID orderId, Part p, String currency) {
    UUID shipmentId = UUID.fromString(p.shipmentId());
    UUID sellerId = p.sellerId() == null ? platformSeller : UUID.fromString(p.sellerId());
    BigDecimal gross =
        p.amount() == null ? BigDecimal.ZERO : p.amount().setScale(2, RoundingMode.HALF_UP);
    Optional<BigDecimal> pct =
        jdbc.sql("SELECT commission_percent FROM sellers WHERE id = :i")
            .param("i", sellerId)
            .query(BigDecimal.class)
            .optional();
    if (pct.isEmpty()) {
      log.warn("Unknown seller {} on shipment {} - skipped", sellerId, shipmentId);
      return;
    }
    if (post(sellerId, "SALE", gross, currency, orderId, shipmentId) == 0) {
      return; // already processed
    }
    BigDecimal commission = gross.multiply(pct.get()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    post(sellerId, "COMMISSION", commission.negate(), currency, orderId, shipmentId);
    BigDecimal fee =
        "INR".equalsIgnoreCase(currency) ? new BigDecimal("10.00") : new BigDecimal("0.25");
    if (pct.get().signum() > 0) { // platform seller (0%) pays no per-shipment fee either
      post(sellerId, "FEE", fee.negate(), currency, orderId, shipmentId);
    }
    if (p.items() != null) {
      for (Item i : p.items()) {
        if (i.productId() == null) {
          continue;
        }
        jdbc.sql(
                "INSERT INTO sale_lines (seller_id, order_id, shipment_id, product_id, name, quantity, amount) VALUES (:s,:o,:sh,:p,:n,:q,:a)")
            .param("s", sellerId)
            .param("o", orderId)
            .param("sh", shipmentId)
            .param("p", UUID.fromString(i.productId()))
            .param("n", i.name())
            .param("q", i.quantity() == null ? 1 : i.quantity())
            .param("a", i.amount() == null ? BigDecimal.ZERO : i.amount())
            .update();
      }
    }
  }

  /**
   * Whole-shipment reversal of the given shipments (or of the whole order when null): books the
   * remainder of each SALE on the shared cap. No event caller until part B; part B must use it or
   * delete it (F-20261119-13 CTO ruling d).
   */
  @Transactional
  public void reverseRefund(UUID orderId, List<UUID> shipmentIds) {
    List<UUID> sales =
        jdbc.sql(
                "SELECT shipment_id FROM ledger_entries WHERE order_id = :o AND type = 'SALE'"
                    + " AND return_id IS NULL ORDER BY shipment_id")
            .param("o", orderId)
            .query(UUID.class)
            .list();
    for (UUID shipmentId : sales) {
      if (shipmentIds == null || shipmentIds.contains(shipmentId)) {
        reverse(orderId, shipmentId, null, Optional.empty(), null);
      }
    }
  }

  /**
   * One return of a shipment (RETURN_REFUNDED): REFUND_REVERSAL = -returnItemsValue (never the
   * customer refund, which includes shipping and discounts), capped at what is left of the SALE.
   * Idempotent per return.
   */
  @Transactional
  public void reverseReturn(
      UUID orderId, UUID shipmentId, UUID returnId, BigDecimal returnItemsValue, String currency) {
    reverse(orderId, shipmentId, returnId, Optional.of(returnItemsValue), currency);
  }

  /**
   * The one reversal core. The SALE row lock serialises every reversal of a shipment, so the
   * remainder read below stays valid until commit. It holds one row and only inserts, so it cannot
   * deadlock with a payout run (which locks rows in one statement and updates them).
   */
  private void reverse(
      UUID orderId, UUID shipmentId, UUID returnId, Optional<BigDecimal> basis, String currency) {
    var sale =
        jdbc.sql(
                "SELECT seller_id, amount, currency FROM ledger_entries WHERE shipment_id = :s"
                    + " AND type = 'SALE' AND return_id IS NULL FOR UPDATE")
            .param("s", shipmentId)
            .query(
                (rs, n) ->
                    new Object[] {
                      rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getString(3)
                    })
            .optional();
    if (sale.isEmpty()) {
      log.warn(
          "Reversal for shipment {} without a booked SALE - reported, nothing posted", shipmentId);
      jdbc.sql(
              "INSERT INTO ledger_discrepancies (shipment_id, order_id, kind, ref, expected, detail)"
                  + " VALUES (:sh, :o, 'UNMATCHED_REVERSAL', :r, :e, 'reversal arrived without a SALE')"
                  + " ON CONFLICT (shipment_id, kind, ref) DO UPDATE SET expected = EXCLUDED.expected,"
                  + " last_seen_at = now()")
          .param("sh", shipmentId)
          .param("o", orderId)
          .param("r", returnId)
          .param("e", basis.orElse(null))
          .update();
      return;
    }
    if (returnId != null
        && jdbc.sql(
                    "SELECT count(*) FROM ledger_entries WHERE return_id = :r AND type = 'REFUND_REVERSAL'")
                .param("r", returnId)
                .query(Long.class)
                .single()
            > 0) {
      return; // replay of this return
    }
    UUID sellerId = (UUID) sale.get()[0];
    BigDecimal saleAmount = (BigDecimal) sale.get()[1];
    String cur = sale.get()[2] == null ? currency : (String) sale.get()[2];
    var sums =
        jdbc.sql(
                "SELECT COALESCE(-SUM(amount) FILTER (WHERE type = 'REFUND_REVERSAL'), 0),"
                    + " COALESCE(-SUM(amount) FILTER (WHERE type = 'COMMISSION'), 0),"
                    + " COALESCE(SUM(amount) FILTER (WHERE type = 'COMMISSION_REVERSAL'), 0)"
                    + " FROM ledger_entries WHERE shipment_id = :s")
            .param("s", shipmentId)
            .query(
                (rs, n) ->
                    new BigDecimal[] {
                      rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)
                    })
            .single();
    BigDecimal commission = sums[1];
    BigDecimal commissionLeft = commission.subtract(sums[2]).max(BigDecimal.ZERO);
    BigDecimal remaining = saleAmount.subtract(sums[0]);
    BigDecimal wanted = basis.map(b -> b.setScale(2, RoundingMode.HALF_UP)).orElse(remaining);
    if (wanted.compareTo(remaining) > 0) {
      log.warn(
          "Reversal of {} on shipment {} capped to the remaining {} (return {})",
          wanted,
          shipmentId,
          remaining,
          returnId);
    }
    BigDecimal amt = wanted.min(remaining);
    if (amt.signum() <= 0) {
      return;
    }
    if (postReturn(sellerId, "REFUND_REVERSAL", amt.negate(), cur, orderId, shipmentId, returnId)
        == 0) {
      return;
    }
    boolean completing = amt.compareTo(remaining) == 0;
    BigDecimal back =
        completing
            ? commissionLeft
            : amt.multiply(commission)
                .divide(saleAmount, 2, RoundingMode.HALF_UP)
                .min(commissionLeft);
    if (back.signum() > 0) {
      postReturn(sellerId, "COMMISSION_REVERSAL", back, cur, orderId, shipmentId, returnId);
    }
    if (feeReversible(completing)) {
      jdbc.sql(
              "INSERT INTO ledger_entries (seller_id, type, amount, currency, order_id, shipment_id)"
                  + " SELECT seller_id, 'FEE_REVERSAL', -amount, currency, order_id, shipment_id"
                  + " FROM ledger_entries WHERE shipment_id = :s AND type = 'FEE' AND return_id IS NULL"
                  + " ON CONFLICT DO NOTHING")
          .param("s", shipmentId)
          .update();
    }
  }

  /**
   * Fee policy, the single decision point. PROVISIONAL (Q1 = B, awaiting Owner confirmation): the
   * booked per-shipment fee is given back once, when the shipment becomes fully reversed. Policy A
   * (never) or C (pro rata) change only this method.
   */
  private boolean feeReversible(boolean completing) {
    return completing;
  }

  private int post(
      UUID sellerId,
      String type,
      BigDecimal amount,
      String currency,
      UUID orderId,
      UUID shipmentId) {
    return postReturn(sellerId, type, amount, currency, orderId, shipmentId, null);
  }

  private int postReturn(
      UUID sellerId,
      String type,
      BigDecimal amount,
      String currency,
      UUID orderId,
      UUID shipmentId,
      UUID returnId) {
    return jdbc.sql(
            "INSERT INTO ledger_entries (seller_id, type, amount, currency, order_id, shipment_id, return_id) VALUES"
                + " (:s,:t,:a,:c,:o,:sh,:r) ON CONFLICT DO NOTHING")
        .param("s", sellerId)
        .param("t", type)
        .param("a", amount)
        .param("c", currency.toUpperCase())
        .param("o", orderId)
        .param("sh", shipmentId)
        .param("r", returnId)
        .update();
  }

  // ---- reads ------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public Paged<LedgerEntry> ledger(UUID sellerId, int page, int size) {
    int s = Paged.clampSize(size);
    long total =
        jdbc.sql("SELECT count(*) FROM ledger_entries WHERE seller_id = :s")
            .param("s", sellerId)
            .query(Long.class)
            .single();
    var rows =
        jdbc.sql(
                "SELECT * FROM ledger_entries WHERE seller_id = :s ORDER BY created_at DESC, type LIMIT :l OFFSET :o")
            .param("s", sellerId)
            .param("l", s)
            .param("o", (long) Math.max(page, 0) * s)
            .query(
                (rs, n) ->
                    new LedgerEntry(
                        rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getObject("order_id", UUID.class),
                        rs.getObject("shipment_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("payout_id", UUID.class)))
            .list();
    return Paged.of(rows, Math.max(page, 0), s, total);
  }

  private static Payout payout(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new Payout(
        rs.getObject("id", UUID.class),
        rs.getObject("seller_id", UUID.class),
        rs.getString("display_name"),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        rs.getString("status"),
        rs.getString("reference"),
        rs.getObject("created_at", OffsetDateTime.class));
  }

  private static final String PAYOUT_SQL =
      "SELECT p.*, s.display_name FROM payouts p JOIN sellers s ON s.id = p.seller_id";

  @Transactional(readOnly = true)
  public Paged<Payout> payouts(UUID sellerId, int page, int size) {
    int s = Paged.clampSize(size);
    String where = sellerId == null ? "" : " WHERE p.seller_id = :s";
    var count = jdbc.sql("SELECT count(*) FROM payouts p" + where);
    var list = jdbc.sql(PAYOUT_SQL + where + " ORDER BY p.created_at DESC LIMIT :l OFFSET :o");
    if (sellerId != null) {
      count = count.param("s", sellerId);
      list = list.param("s", sellerId);
    }
    long total = count.query(Long.class).single();
    return Paged.of(
        list.param("l", s)
            .param("o", (long) Math.max(page, 0) * s)
            .query(LedgerService::payout)
            .list(),
        Math.max(page, 0),
        s,
        total);
  }

  // ---- payout run -------------------------------------------------------------------------

  @Transactional
  public PayoutRun run(Integer minAgeDays) {
    int days = minAgeDays == null ? defaultMinAgeDays : Math.max(0, minAgeDays);
    OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
    record Row(UUID id, UUID sellerId, String currency, BigDecimal amount) {}
    List<Row> rows =
        jdbc.sql(
                "SELECT id, seller_id, currency, amount FROM ledger_entries WHERE payout_id IS NULL AND created_at <= :c FOR UPDATE")
            .param("c", cutoff)
            .query(
                (rs, n) ->
                    new Row(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        rs.getBigDecimal(4)))
            .list();
    Map<String, List<Row>> groups = new LinkedHashMap<>();
    for (Row r : rows) {
      groups.computeIfAbsent(r.sellerId() + "|" + r.currency(), k -> new ArrayList<>()).add(r);
    }
    List<Payout> created = new ArrayList<>();
    for (List<Row> g : groups.values()) {
      BigDecimal sum = g.stream().map(Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
      if (sum.signum() <= 0) {
        continue; // negative balances carry forward
      }
      UUID id = UUID.randomUUID();
      String ref =
          "UTR"
              + String.format(
                  "%012d", Math.abs(new java.util.Random().nextLong()) % 1_000_000_000_000L);
      jdbc.sql(
              "INSERT INTO payouts (id, seller_id, amount, currency, status, reference) VALUES (:i,:s,:a,:c,'PAID',:r)")
          .param("i", id)
          .param("s", g.get(0).sellerId())
          .param("a", sum)
          .param("c", g.get(0).currency())
          .param("r", ref)
          .update();
      jdbc.sql("UPDATE ledger_entries SET payout_id = :p WHERE id IN (:ids)")
          .param("p", id)
          .param("ids", g.stream().map(Row::id).toList())
          .update();
      created.add(
          jdbc.sql(PAYOUT_SQL + " WHERE p.id = :i")
              .param("i", id)
              .query(LedgerService::payout)
              .single());
    }
    log.info("Payout run (minAgeDays={}) created {} payouts", days, created.size());
    return new PayoutRun(created.size(), created);
  }
}
