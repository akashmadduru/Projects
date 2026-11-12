package com.acme.ecommerce.payment.service;

import com.acme.ecommerce.payment.api.dto.PaymentResultRequest;
import com.acme.ecommerce.payment.client.OrderFeignClient;
import com.acme.ecommerce.payment.repository.PaymentRepository;
import com.acme.ecommerce.payment.repository.PaymentRepository.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers SUCCEEDED/FAILED outcomes to order-service. Retry-safe: the {@code reported} flag stays
 * false until order-service accepted the call; failures are swallowed and retried by the scheduler
 * (and on the next owner GET). order-service's endpoint is idempotent.
 */
@Component
public class OrderReporter {

  private static final Logger log = LoggerFactory.getLogger(OrderReporter.class);

  private final PaymentRepository payments;
  private final OrderFeignClient orders;

  public OrderReporter(PaymentRepository payments, OrderFeignClient orders) {
    this.payments = payments;
    this.orders = orders;
  }

  /** Best effort; never throws. */
  public void report(Payment p) {
    if (p.reported()
        || !"CARD".equals(p.method())
        || !("SUCCEEDED".equals(p.status()) || "FAILED".equals(p.status()))) {
      return;
    }
    try {
      orders.paymentResult(p.orderId(), new PaymentResultRequest(p.status(), p.id()));
      payments.markReported(p.id());
      log.info("Reported payment {} ({}) to order-service", p.id(), p.status());
    } catch (Exception e) {
      payments.bumpAttempts(p.id());
      log.warn(
          "Could not report payment {} for order {} to order-service (will retry): {}",
          p.id(),
          p.orderId(),
          e.getMessage());
    }
  }

  @Scheduled(
      fixedDelayString = "${payment.reporter.interval-ms:30000}",
      initialDelayString = "${payment.reporter.interval-ms:30000}")
  public void retryPending() {
    for (Payment p : payments.findUnreported(50)) {
      report(p);
    }
  }
}
