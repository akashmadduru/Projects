package com.acme.ecommerce.product.stock;

import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.product.client.InventoryDto;
import com.acme.ecommerce.product.client.InventoryServiceClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Makes sure inventory-service has a stock row for every active product and every variant
 * (TD-P6-04, TD-P7-07).
 *
 * <p>Inventory fails closed: an order line with no stock row is out of stock. Rows are normally
 * created from PRODUCT_CREATED, but seed data, variants added in the editor and lost events never
 * get one. This job walks the catalog and asks inventory-service to create the missing rows (it
 * never changes an existing quantity, so repeating it is safe). Polling the database covers every
 * write path, the same reasoning as the embedding reconciler.
 */
@Component
public class CatalogStockSync {

  private static final Logger log = LoggerFactory.getLogger(CatalogStockSync.class);

  /** Products per page; each page's products and variants go to inventory in chunks. */
  static final int PAGE = 250;

  static final int CHUNK = 500;

  private final JdbcClient jdbc;
  private final InventoryServiceClient inventory;
  private final JobLock jobLock;
  private final String internalToken;
  private final boolean enabled;

  public CatalogStockSync(
      JdbcClient jdbc,
      InventoryServiceClient inventory,
      JobLock jobLock,
      @Value("${INTERNAL_API_TOKEN:}") String internalToken,
      @Value("${product.stock-sync.enabled:true}") boolean enabled) {
    this.jdbc = jdbc;
    this.inventory = inventory;
    this.jobLock = jobLock;
    this.internalToken = internalToken;
    this.enabled = enabled;
  }

  @Scheduled(
      initialDelayString = "${product.stock-sync.initial-delay:PT1M}",
      fixedDelayString = "${product.stock-sync.interval:PT15M}")
  public void scheduledRun() {
    if (!enabled) {
      return;
    }
    try {
      jobLock.runExclusive("catalog-stock-sync", this::sync);
    } catch (RuntimeException e) {
      // inventory-service down: the next run tries again; lines stay out of stock meanwhile.
      log.warn("Catalog stock sync failed: {}", e.toString());
    }
  }

  /** One full pass. Returns how many rows inventory-service created. */
  public int sync() {
    int created = 0;
    // The all-zero UUID sorts before every other one in Postgres.
    UUID after = new UUID(0L, 0L);
    while (true) {
      List<UUID> products =
          jdbc.sql(
                  "SELECT id FROM product.products WHERE active AND id > :after ORDER BY id LIMIT :lim")
              .param("after", after)
              .param("lim", PAGE)
              .query(UUID.class)
              .list();
      if (products.isEmpty()) {
        break;
      }
      List<InventoryDto.EnsureItem> items = new ArrayList<>();
      for (UUID p : products) {
        items.add(new InventoryDto.EnsureItem(p, null));
      }
      jdbc.sql(
              "SELECT product_id, id FROM product.product_variants WHERE product_id IN (:ids)"
                  + " ORDER BY product_id, id")
          .param("ids", products)
          .query(
              (rs, n) ->
                  new InventoryDto.EnsureItem(
                      rs.getObject("product_id", UUID.class), rs.getObject("id", UUID.class)))
          .list()
          .forEach(items::add);
      for (int i = 0; i < items.size(); i += CHUNK) {
        InventoryDto.EnsureResult r =
            inventory.ensureStock(
                new InventoryDto.EnsureRequest(items.subList(i, Math.min(items.size(), i + CHUNK))),
                internalToken);
        created += r == null ? 0 : r.created();
      }
      after = products.get(products.size() - 1);
      if (products.size() < PAGE) {
        break;
      }
    }
    if (created > 0) {
      log.info("Catalog stock sync: inventory created {} missing stock row(s)", created);
    }
    return created;
  }
}
