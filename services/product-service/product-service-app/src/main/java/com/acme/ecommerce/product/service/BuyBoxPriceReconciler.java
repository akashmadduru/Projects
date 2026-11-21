package com.acme.ecommerce.product.service;

import com.acme.ecommerce.common.web.job.JobLock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically re-runs {@link OfferService#syncPrices} for every product with an active seller
 * offer (TD-P9-06). The list-row price follows the buy box only when an offer or seller stock write
 * triggers a sync; a seller offer that sells out through orders, a platform stock write, or a sync
 * call lost to an outage left list and search rows at a stale price until the next change. This
 * pass bounds that staleness to {@code product.buy-box.reconcile-interval} (default 15 minutes). A
 * failure for one product is logged and the pass continues.
 */
@Component
public class BuyBoxPriceReconciler {

  private static final Logger log = LoggerFactory.getLogger(BuyBoxPriceReconciler.class);

  private final JdbcClient jdbc;
  private final OfferService offers;
  private final JobLock jobLock;

  public BuyBoxPriceReconciler(JdbcClient jdbc, OfferService offers, JobLock jobLock) {
    this.jdbc = jdbc;
    this.offers = offers;
    this.jobLock = jobLock;
  }

  @Scheduled(
      initialDelayString = "${product.buy-box.reconcile-initial-delay:5m}",
      fixedDelayString = "${product.buy-box.reconcile-interval:15m}")
  public void scheduled() {
    jobLock.runExclusive("product-buy-box-reconcile", this::reconcile);
  }

  /** Returns how many products were re-synced. */
  public int reconcile() {
    List<UUID> ids =
        jdbc.sql("SELECT DISTINCT product_id FROM product.offers WHERE active")
            .query(UUID.class)
            .list();
    int done = 0;
    for (UUID id : ids) {
      try {
        offers.syncPrices(id);
        done++;
      } catch (RuntimeException e) {
        log.warn("Buy-box price reconcile failed for product {}: {}", id, e.toString());
      }
    }
    if (!ids.isEmpty()) {
      log.debug("Buy-box price reconcile: {} of {} product(s) synced", done, ids.size());
    }
    return done;
  }
}
