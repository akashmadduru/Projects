package com.acme.ecommerce.order.job;

import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.order.service.OrderStore;
import com.acme.ecommerce.order.service.ReturnService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Settles return refunds whose card leg is still PENDING (F-04) by replaying the refund key:
 * payment-service answers from its own table, so a poll never creates a second refund. One replica
 * at a time (JobLock).
 */
@Component
public class ReturnRefundWatch {

  private static final Logger log = LoggerFactory.getLogger(ReturnRefundWatch.class);

  private final OrderStore store;
  private final ReturnService returns;
  private final JobLock jobLock;

  public ReturnRefundWatch(OrderStore store, ReturnService returns, JobLock jobLock) {
    this.store = store;
    this.returns = returns;
    this.jobLock = jobLock;
  }

  @Scheduled(
      fixedDelayString = "${order.returns.refund-poll-interval-ms:300000}",
      initialDelay = 60_000)
  public void poll() {
    jobLock.runExclusive(
        "order-return-refund-watch", () -> store.pendingRestLegIds().forEach(this::check));
  }

  private void check(UUID id) {
    try {
      returns.resumePending(id);
    } catch (RuntimeException e) {
      log.warn("Return refund {} could not be checked, will retry: {}", id, e.toString());
    }
  }
}
