package com.acme.ecommerce.inventory.service;

import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.inventory.domain.Platform;
import com.acme.ecommerce.inventory.event.InventoryEventPublisher;
import com.acme.ecommerce.inventory.event.InventoryEventType;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes {@code warehouse_stock} the single source of truth for platform stock (TD-P9-02, step 1).
 *
 * <p>The legacy totals ({@code stock_levels.quantity} per product, {@code variant_stock.quantity}
 * per variant) still back {@code /api/inventory/**} and the inventory events, and the code keeps
 * them in step by hand on every reservation and stock write. Anything that misses that step leaves
 * the two disagreeing. This job recomputes every legacy total from the platform's warehouse rows
 * and overwrites any that differ, bumping {@code stock_levels.version} so a concurrent editor gets
 * an optimistic-lock conflict instead of silently writing the stale value back. Each repaired
 * product publishes INVENTORY_QUANTITY_CHANGED so product-service's cached quantity follows.
 */
@Component
public class StockTotalsReconciler {

  private static final Logger log = LoggerFactory.getLogger(StockTotalsReconciler.class);

  private final JdbcClient jdbc;
  private final InventoryEventPublisher events;
  private final JobLock jobLock;

  public StockTotalsReconciler(JdbcClient jdbc, InventoryEventPublisher events, JobLock jobLock) {
    this.jdbc = jdbc;
    this.events = events;
    this.jobLock = jobLock;
  }

  @Scheduled(
      initialDelayString = "${inventory.stock.reconcile-initial-delay:PT2M}",
      fixedDelayString = "${inventory.stock.reconcile-interval:PT1H}")
  public void scheduledRun() {
    try {
      jobLock.runExclusive("inventory-stock-totals", this::reconcile);
    } catch (RuntimeException e) {
      log.warn("Stock totals reconcile failed: {}", e.toString());
    }
  }

  public record Result(int products, int variants) {}

  /** One pass; returns how many legacy totals were corrected. */
  @Transactional
  public Result reconcile() {
    var products =
        jdbc.sql(
                "UPDATE inventory.stock_levels s SET quantity = t.q, version = s.version + 1,"
                    + " updated_at = now()"
                    + " FROM (SELECT product_id, SUM(quantity)::int AS q"
                    + "       FROM inventory.warehouse_stock"
                    + "       WHERE seller_id = :s AND variant_id IS NULL GROUP BY product_id) t"
                    + " WHERE s.product_id = t.product_id AND s.quantity <> t.q"
                    + " RETURNING s.product_id, s.quantity")
            .param("s", Platform.SELLER_ID)
            .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getInt(2)})
            .list();
    var variants =
        jdbc.sql(
                "UPDATE inventory.variant_stock v SET quantity = t.q, updated_at = now()"
                    + " FROM (SELECT variant_id, MIN(product_id::text)::uuid AS product_id,"
                    + "              SUM(quantity)::int AS q"
                    + "       FROM inventory.warehouse_stock"
                    + "       WHERE seller_id = :s AND variant_id IS NOT NULL GROUP BY variant_id) t"
                    + " WHERE v.variant_id = t.variant_id AND v.quantity <> t.q"
                    + " RETURNING v.variant_id, t.product_id, v.quantity")
            .param("s", Platform.SELLER_ID)
            .query(
                (rs, n) ->
                    new Object[] {
                      rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3)
                    })
            .list();
    for (Object[] p : products) {
      events.publishAfterCommit(
          InventoryEventType.INVENTORY_QUANTITY_CHANGED, (UUID) p[0], (Integer) p[1]);
    }
    for (Object[] v : variants) {
      events.publishAfterCommit(
          InventoryEventType.INVENTORY_QUANTITY_CHANGED, (UUID) v[1], (UUID) v[0], (Integer) v[2]);
    }
    if (!products.isEmpty() || !variants.isEmpty()) {
      log.warn(
          "Stock totals drifted from warehouse stock; corrected {} product and {} variant total(s)",
          products.size(),
          variants.size());
    }
    return new Result(products.size(), variants.size());
  }
}
