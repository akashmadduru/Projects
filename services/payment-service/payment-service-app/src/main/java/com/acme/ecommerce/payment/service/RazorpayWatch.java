package com.acme.ecommerce.payment.service;

import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.payment.config.PaymentProperties;
import com.acme.ecommerce.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Operator alerts for two Razorpay situations that nothing else would surface. It only logs: it
 * never changes a payment or refund status. Each alert is claimed with one UPDATE ... RETURNING, so
 * it fires once even with several replicas or repeated ticks.
 *
 * <ul>
 *   <li>RAZORPAY NOT CAPTURED: a payment.authorized was seen and the payment is still unpaid after
 *       {@code captureAlertAfter} (manual capture is on in the Razorpay dashboard). The event alone
 *       is not enough: Razorpay may send it after, or with, the capture.
 *   <li>REFUND PENDING TOO LONG: a refund is still PENDING after {@code refundPendingAlertAfter}
 *       (refund webhooks are probably not subscribed).
 * </ul>
 */
@Component
public class RazorpayWatch {

  private static final Logger log = LoggerFactory.getLogger(RazorpayWatch.class);
  private static final int BATCH = 50;

  private final PaymentRepository payments;
  private final PaymentProperties properties;
  private final JobLock jobLock;

  public RazorpayWatch(PaymentRepository payments, PaymentProperties properties, JobLock jobLock) {
    this.payments = payments;
    this.properties = properties;
    this.jobLock = jobLock;
  }

  @Scheduled(
      fixedDelayString = "${payment.razorpay.watch-interval-ms:300000}",
      initialDelayString = "${payment.razorpay.watch-interval-ms:300000}")
  public void tick() {
    jobLock.runExclusive("payment-razorpay-watch", this::runOnce);
  }

  /** One pass, without the cluster lock. */
  void runOnce() {
    var cfg = properties.getRazorpay();
    for (var a : payments.claimUncaptured(seconds(cfg.getCaptureAlertAfter()), BATCH)) {
      log.error(
          "RAZORPAY NOT CAPTURED: order {} ({} {}, Razorpay order {}) was authorized but is still"
              + " unpaid; enable automatic capture in the Razorpay dashboard and capture or refund"
              + " it by hand",
          a.orderId(),
          a.amount(),
          a.currency(),
          a.providerId());
    }
    for (var a : payments.claimStuckRefunds(seconds(cfg.getRefundPendingAlertAfter()), BATCH)) {
      log.error(
          "REFUND PENDING TOO LONG: Razorpay refund {} of {} {} for order {} is still PENDING;"
              + " check the refund in the Razorpay dashboard and that the refund webhooks are"
              + " subscribed",
          a.providerId(),
          a.amount(),
          a.currency(),
          a.orderId());
    }
  }

  private static double seconds(java.time.Duration d) {
    return d.toMillis() / 1000.0;
  }
}
