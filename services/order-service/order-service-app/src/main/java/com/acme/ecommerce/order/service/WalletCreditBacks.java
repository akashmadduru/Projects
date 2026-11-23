package com.acme.ecommerce.order.service;

import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.order.client.PaymentFeignClient;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Durable "give the wallet amount back" (TD-P8-07).
 *
 * <p>The credit is written to {@code orders.pending_wallet_credits} first, then attempted. A
 * failure (payment-service down) leaves the row due again with exponential backoff, capped at an
 * hour; a background job retries due rows every minute on one replica. payment-service's credit is
 * idempotent by key, so a retry after an ambiguous failure (timeout after the credit landed) is
 * harmless. After {@link #ALERT_AFTER} failures each further failure logs at ERROR with "WALLET
 * CREDIT STUCK" so it can be alerted on; the row is never given up.
 */
@Component
public class WalletCreditBacks {

  private static final Logger log = LoggerFactory.getLogger(WalletCreditBacks.class);

  static final int ALERT_AFTER = 10;

  private static final int MAX_BACKOFF_SECONDS = 3600;

  private final JdbcClient jdbc;
  private final PaymentFeignClient payments;
  private final JobLock jobLock;

  public WalletCreditBacks(JdbcClient jdbc, PaymentFeignClient payments, JobLock jobLock) {
    this.jdbc = jdbc;
    this.payments = payments;
    this.jobLock = jobLock;
  }

  /**
   * The wallet debit of an order failed ambiguously (TD-P8-23): ask payment-service to give it back
   * only if it actually happened. Durable and retried like {@link #creditBack}, and shares its key,
   * so a debit is returned at most once whichever path runs.
   */
  public void reverseDebit(UUID userId, BigDecimal amount, String currency, UUID orderId) {
    String key = "order-wallet-back-" + orderId;
    try {
      jdbc.sql(
              "INSERT INTO orders.pending_wallet_credits"
                  + " (order_id, user_id, amount, currency, idem_key, kind, debit_key)"
                  + " VALUES (:o, :u, :a, :c, :k, 'REVERSE_DEBIT', :d)"
                  + " ON CONFLICT (idem_key) DO NOTHING")
          .param("o", orderId)
          .param("u", userId)
          .param("a", amount)
          .param("c", currency)
          .param("k", key)
          .param("d", "order-debit-" + orderId)
          .update();
    } catch (RuntimeException e) {
      log.error("WALLET REVERSAL NOT RECORDED for order {}: {}", orderId, e.toString());
      return;
    }
    attempt(key);
  }

  /** Records the credit and tries it once now. Never throws for a payment-service failure. */
  public void creditBack(UUID userId, BigDecimal amount, String currency, UUID orderId) {
    String key = "order-wallet-back-" + orderId;
    try {
      jdbc.sql(
              "INSERT INTO orders.pending_wallet_credits"
                  + " (order_id, user_id, amount, currency, idem_key)"
                  + " VALUES (:o, :u, :a, :c, :k) ON CONFLICT (idem_key) DO NOTHING")
          .param("o", orderId)
          .param("u", userId)
          .param("a", amount)
          .param("c", currency)
          .param("k", key)
          .update();
    } catch (RuntimeException e) {
      // Could not record it (order database trouble): one direct attempt, loudly logged.
      log.error("WALLET CREDIT NOT RECORDED for order {}: {}", orderId, e.toString());
      try {
        payments.walletCredit(
            new PaymentFeignClient.WalletCredit(
                userId, amount, currency, "REFUND", orderId.toString(), key));
      } catch (RuntimeException again) {
        log.error(
            "WALLET CREDIT STUCK: {} {} for order {} could not be returned: {}",
            amount,
            currency,
            orderId,
            again.toString());
      }
      return;
    }
    attempt(key);
  }

  /** Insert-only, joins the caller's transaction: the durable credit commits with the state change. */
  public void enqueue(UUID userId, BigDecimal amount, String currency, UUID orderId, String key) {
    jdbc.sql(
            "INSERT INTO orders.pending_wallet_credits"
                + " (order_id, user_id, amount, currency, idem_key)"
                + " VALUES (:o, :u, :a, :c, :k) ON CONFLICT (idem_key) DO NOTHING")
        .param("o", orderId)
        .param("u", userId)
        .param("a", amount)
        .param("c", currency)
        .param("k", key)
        .update();
  }

  public void tryNow(String key) {
    try {
      attempt(key);
    } catch (RuntimeException e) {
      log.warn("Wallet credit {} could not be attempted now: {}", key, e.toString());
    }
  }

  @Scheduled(
      fixedDelayString = "${order.wallet-credit-retry-interval-ms:60000}",
      initialDelay = 60_000)
  public void retryDue() {
    jobLock.runExclusive("order-wallet-credit-backs", this::retryDueLocked);
  }

  void retryDueLocked() {
    jdbc.sql(
            "SELECT idem_key FROM orders.pending_wallet_credits WHERE completed_at IS NULL"
                + " AND next_attempt_at <= now() ORDER BY next_attempt_at LIMIT 50")
        .query(String.class)
        .list()
        .forEach(this::attempt);
  }

  record Pending(
      UUID userId,
      BigDecimal amount,
      String currency,
      UUID orderId,
      int attempts,
      String kind,
      String debitKey) {

    Pending(UUID userId, BigDecimal amount, String currency, UUID orderId, int attempts) {
      this(userId, amount, currency, orderId, attempts, "CREDIT", null);
    }
  }

  private void attempt(String key) {
    Pending p =
        jdbc.sql(
                "SELECT user_id, amount, currency, order_id, attempts, kind, debit_key FROM"
                    + " orders.pending_wallet_credits WHERE idem_key = :k AND completed_at IS NULL")
            .param("k", key)
            .query(
                (rs, n) ->
                    new Pending(
                        rs.getObject("user_id", UUID.class),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getObject("order_id", UUID.class),
                        rs.getInt("attempts"),
                        rs.getString("kind"),
                        rs.getString("debit_key")))
            .optional()
            .orElse(null);
    if (p == null) {
      return;
    }
    try {
      if ("REVERSE_DEBIT".equals(p.kind())) {
        // Returns money only if the debit happened; "nothing to reverse" is also success.
        payments.walletReverse(new PaymentFeignClient.WalletReverse(p.userId(), p.debitKey(), key));
      } else {
        payments.walletCredit(
            new PaymentFeignClient.WalletCredit(
                p.userId(), p.amount(), p.currency(), "REFUND", p.orderId().toString(), key));
      }
      jdbc.sql(
              "UPDATE orders.pending_wallet_credits SET completed_at = now(),"
                  + " attempts = attempts + 1, last_error = NULL WHERE idem_key = :k")
          .param("k", key)
          .update();
    } catch (RuntimeException e) {
      int attempts = p.attempts() + 1;
      int backoff = (int) Math.min(MAX_BACKOFF_SECONDS, 30L * (1L << Math.min(attempts, 20)));
      String error = e.toString();
      jdbc.sql(
              "UPDATE orders.pending_wallet_credits SET attempts = :n, last_error = :e,"
                  + " next_attempt_at = now() + make_interval(secs => :b) WHERE idem_key = :k")
          .param("n", attempts)
          .param("e", error.length() > 500 ? error.substring(0, 500) : error)
          .param("b", backoff)
          .param("k", key)
          .update();
      if (attempts >= ALERT_AFTER) {
        log.error(
            "WALLET CREDIT STUCK: {} {} for order {} not returned after {} attempts: {}",
            p.amount(),
            p.currency(),
            p.orderId(),
            attempts,
            error);
      } else {
        log.warn(
            "Wallet credit for order {} failed (attempt {}), retrying in {}s: {}",
            p.orderId(),
            attempts,
            backoff,
            error);
      }
    }
  }
}
