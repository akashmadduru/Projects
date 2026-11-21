package com.acme.ecommerce.cart.service;

import com.acme.ecommerce.common.web.job.JobLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes carts nobody has touched for {@code cart.idle-purge-days} (default 180; 0 turns it off),
 * TD-BE-11. Prices and stock in such a cart are long stale and order-service re-prices anyway, so
 * nothing of value is lost. Wishlists, saved-for-later items and lists are kept: those are things
 * the customer chose to keep. Deleting the cart row cascades to its items; a returning customer
 * simply gets a new empty cart.
 */
@Component
public class IdleCartPurge {

  private static final Logger log = LoggerFactory.getLogger(IdleCartPurge.class);

  private final JdbcClient jdbc;
  private final JobLock jobLock;
  private final int idleDays;

  public IdleCartPurge(
      JdbcClient jdbc, JobLock jobLock, @Value("${cart.idle-purge-days:180}") int idleDays) {
    this.jdbc = jdbc;
    this.jobLock = jobLock;
    this.idleDays = idleDays;
  }

  @Scheduled(cron = "${cart.idle-purge-cron:0 40 3 * * *}")
  public void scheduled() {
    if (idleDays > 0) {
      jobLock.runExclusive("cart-idle-purge", this::purge);
    }
  }

  /** Returns the number of carts deleted. */
  @Transactional
  public int purge() {
    int n =
        jdbc.sql("DELETE FROM cart.carts WHERE updated_at < now() - make_interval(days => :d)")
            .param("d", idleDays)
            .update();
    if (n > 0) {
      log.info("Purged {} cart(s) idle for more than {} days", n, idleDays);
    }
    return n;
  }
}
