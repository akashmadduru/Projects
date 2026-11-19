package com.acme.ecommerce.payment.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Payment persistence on plain SQL. */
@Repository
public class PaymentRepository {

  /** Row view. */
  public record Payment(
      UUID id,
      UUID orderId,
      UUID userId,
      String method,
      String mode,
      BigDecimal amount,
      String currency,
      String status,
      String intentId,
      BigDecimal refundedAmount,
      boolean reported,
      String purpose,
      UUID referenceId,
      String providerOrderId,
      String providerPaymentId) {}

  private static final String COLS =
      "id, order_id, user_id, method, mode, amount, currency, status, "
          + "stripe_payment_intent_id, refunded_amount, reported, purpose, reference_id, "
          + "provider_order_id, provider_payment_id";

  private final JdbcClient jdbc;

  public PaymentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static Payment map(ResultSet rs, int n) throws SQLException {
    return new Payment(
        rs.getObject("id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("method"),
        rs.getString("mode"),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        rs.getString("status"),
        rs.getString("stripe_payment_intent_id"),
        rs.getBigDecimal("refunded_amount"),
        rs.getBoolean("reported"),
        rs.getString("purpose"),
        rs.getObject("reference_id", UUID.class),
        rs.getString("provider_order_id"),
        rs.getString("provider_payment_id"));
  }

  public Optional<Payment> findByOrderId(UUID orderId) {
    return jdbc.sql("SELECT " + COLS + " FROM payment.payments WHERE order_id = :o")
        .param("o", orderId)
        .query(PaymentRepository::map)
        .optional();
  }

  public Optional<Payment> findById(UUID id) {
    return jdbc.sql("SELECT " + COLS + " FROM payment.payments WHERE id = :i")
        .param("i", id)
        .query(PaymentRepository::map)
        .optional();
  }

  public Optional<Payment> findByIntentId(String intentId) {
    return jdbc.sql("SELECT " + COLS + " FROM payment.payments WHERE stripe_payment_intent_id = :i")
        .param("i", intentId)
        .query(PaymentRepository::map)
        .optional();
  }

  public Optional<Payment> findByProviderOrderId(String providerOrderId) {
    return jdbc.sql("SELECT " + COLS + " FROM payment.payments WHERE provider_order_id = :i")
        .param("i", providerOrderId)
        .query(PaymentRepository::map)
        .optional();
  }

  /** Row lock for the refund critical section. */
  public Optional<Payment> lockById(UUID id) {
    return jdbc.sql("SELECT " + COLS + " FROM payment.payments WHERE id = :i FOR UPDATE")
        .param("i", id)
        .query(PaymentRepository::map)
        .optional();
  }

  /** Returns rows inserted (0 when a payment for the order already exists). */
  public int insert(
      UUID orderId,
      UUID userId,
      String method,
      String mode,
      BigDecimal amount,
      String currency,
      String status,
      String intentId,
      String providerOrderId,
      String purpose) {
    return jdbc.sql(
            "INSERT INTO payment.payments (order_id, user_id, method, mode, amount, currency, "
                + "status, stripe_payment_intent_id, provider_order_id, purpose, reference_id) "
                + "VALUES (:o, :u, :m, :mode, :a, :c, :s, :i, :po, :p, :o) "
                + "ON CONFLICT (order_id) DO NOTHING")
        .param("o", orderId)
        .param("u", userId)
        .param("m", method)
        .param("mode", mode)
        .param("a", amount)
        .param("c", currency)
        .param("s", status)
        .param("p", purpose)
        .param("i", intentId)
        .param("po", providerOrderId)
        .update();
  }

  /**
   * Customer picked another provider before paying. Only while still awaiting payment. Returns rows
   * changed. The id of the provider moved away from is kept so a late webhook can still find the
   * payment.
   */
  public int switchProvider(UUID id, String mode, String intentId, String providerOrderId) {
    return jdbc.sql(
            "UPDATE payment.payments SET mode = :m, "
                + "stripe_payment_intent_id = COALESCE(:i, stripe_payment_intent_id), "
                + "provider_order_id = COALESCE(:po, provider_order_id), updated_at = now() "
                + "WHERE id = :id AND status = 'REQUIRES_PAYMENT' AND method = 'CARD'")
        .param("m", mode)
        .param("i", intentId)
        .param("po", providerOrderId)
        .param("id", id)
        .update();
  }

  /** Remembers the Razorpay payment that settled a payment (needed to refund it). */
  public void setProviderPaymentId(UUID id, String providerPaymentId) {
    jdbc.sql(
            "UPDATE payment.payments SET provider_payment_id = :p WHERE id = :i "
                + "AND provider_payment_id IS NULL")
        .param("p", providerPaymentId)
        .param("i", id)
        .update();
  }

  /**
   * Moves a payment still awaiting an outcome to SUCCEEDED/FAILED and flags it for reporting.
   * Returns rows changed (0 when it already had an outcome).
   */
  public int applyOutcome(UUID id, String status) {
    return jdbc.sql(
            "UPDATE payment.payments SET status = :s, reported = false, updated_at = now() "
                + "WHERE id = :i AND status IN ('REQUIRES_PAYMENT', 'PROCESSING')")
        .param("s", status)
        .param("i", id)
        .update();
  }

  /**
   * Voids a payment that was never paid (its order was cancelled). Marked reported: order-service
   * already knows, so the reporter must not send it a FAILED outcome.
   */
  public int voidUnpaid(UUID id) {
    return jdbc.sql(
            "UPDATE payment.payments SET status = 'FAILED', reported = true, updated_at = now() "
                + "WHERE id = :i AND status IN ('REQUIRES_PAYMENT', 'PROCESSING')")
        .param("i", id)
        .update();
  }

  public int markCodCollected(UUID id) {
    return jdbc.sql(
            "UPDATE payment.payments SET status = 'SUCCEEDED', reported = true, "
                + "updated_at = now() WHERE id = :i AND status = 'COD_PENDING'")
        .param("i", id)
        .update();
  }

  public void markReported(UUID id) {
    jdbc.sql("UPDATE payment.payments SET reported = true, updated_at = now() WHERE id = :i")
        .param("i", id)
        .update();
  }

  /** Maximum report attempts before the reporter gives up; see {@link #findUnreported}. */
  public static final int MAX_REPORT_ATTEMPTS = 200;

  /** Increments the attempt counter and returns the new value (0 if the row is gone). */
  public int bumpAttempts(UUID id) {
    return jdbc.sql(
            "UPDATE payment.payments SET report_attempts = report_attempts + 1, "
                + "updated_at = now() WHERE id = :i RETURNING report_attempts")
        .param("i", id)
        .query(Integer.class)
        .optional()
        .orElse(0);
  }

  /** CARD outcomes not yet delivered to order-service, oldest first. */
  public List<Payment> findUnreported(int limit) {
    return jdbc.sql(
            "SELECT "
                + COLS
                + " FROM payment.payments WHERE reported = false AND method = 'CARD' "
                + "AND status IN ('SUCCEEDED', 'FAILED') AND report_attempts < :max ORDER BY updated_at LIMIT :l")
        .param("max", MAX_REPORT_ATTEMPTS)
        .param("l", limit)
        .query(PaymentRepository::map)
        .list();
  }

  public void applyRefund(UUID id, BigDecimal newRefunded, String status) {
    jdbc.sql(
            "UPDATE payment.payments SET refunded_amount = :r, status = :s, updated_at = now() "
                + "WHERE id = :i")
        .param("r", newRefunded)
        .param("s", status)
        .param("i", id)
        .update();
  }

  public boolean refundExists(String idempotencyKey) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM payment.refunds WHERE idempotency_key = :k)")
        .param("k", idempotencyKey)
        .query(Boolean.class)
        .single();
  }

  /** A refund row. Status is PENDING until Razorpay confirms, then SUCCEEDED or FAILED. */
  public record Refund(
      UUID id, UUID paymentId, BigDecimal amount, String status, String providerRefundId) {}

  /** A refund event that arrived before its refund row existed. */
  public record ParkedRefundEvent(
      String status, String providerPaymentId, long amountMinor, String currency) {}

  private static Refund mapRefund(ResultSet rs, int n) throws SQLException {
    return new Refund(
        rs.getObject("id", UUID.class),
        rs.getObject("payment_id", UUID.class),
        rs.getBigDecimal("amount"),
        rs.getString("status"),
        rs.getString("provider_refund_id"));
  }

  public void insertRefund(
      UUID paymentId,
      BigDecimal amount,
      String reason,
      String stripeRefundId,
      String providerRefundId,
      String key,
      String status) {
    jdbc.sql(
            "INSERT INTO payment.refunds (payment_id, amount, reason, stripe_refund_id, "
                + "provider_refund_id, idempotency_key, status) "
                + "VALUES (:p, :a, :r, :s, :pr, :k, :st)")
        .param("p", paymentId)
        .param("a", amount)
        .param("r", reason)
        .param("s", stripeRefundId)
        .param("pr", providerRefundId)
        .param("k", key)
        .param("st", status)
        .update();
  }

  /** Row lock on the refund; call after the payment lock (same order as refund()). */
  public Optional<Refund> findRefundByProviderRefundId(String providerRefundId) {
    return jdbc.sql(
            "SELECT id, payment_id, amount, status, provider_refund_id FROM payment.refunds "
                + "WHERE provider_refund_id = :r ORDER BY created_at LIMIT 1 FOR UPDATE")
        .param("r", providerRefundId)
        .query(PaymentRepository::mapRefund)
        .optional();
  }

  public Optional<Payment> findByProviderPaymentIdForUpdate(String providerPaymentId) {
    return jdbc.sql(
            "SELECT "
                + COLS
                + " FROM payment.payments WHERE provider_payment_id = :p LIMIT 1 FOR UPDATE")
        .param("p", providerPaymentId)
        .query(PaymentRepository::map)
        .optional();
  }

  /** PENDING to a terminal status, once. Returns rows changed (0: already settled). */
  public int settleRefundRow(UUID id, String status) {
    return jdbc.sql(
            "UPDATE payment.refunds SET status = :s, settled_at = now() "
                + "WHERE id = :i AND status = 'PENDING'")
        .param("s", status)
        .param("i", id)
        .update();
  }

  public String refundStatus(UUID id) {
    return jdbc.sql("SELECT status FROM payment.refunds WHERE id = :i")
        .param("i", id)
        .query(String.class)
        .single();
  }

  public void parkRefundEvent(
      String providerRefundId,
      String status,
      String providerPaymentId,
      long amountMinor,
      String currency) {
    jdbc.sql(
            "INSERT INTO payment.razorpay_refund_parked (provider_refund_id, status, "
                + "provider_payment_id, amount_minor, currency) VALUES (:r, :s, :p, :a, :c) "
                + "ON CONFLICT DO NOTHING")
        .param("r", providerRefundId)
        .param("s", status)
        .param("p", providerPaymentId)
        .param("a", amountMinor)
        .param("c", currency)
        .update();
  }

  /** Removes and returns the parked events of a refund, oldest first. */
  public List<ParkedRefundEvent> takeParkedRefundEvents(String providerRefundId) {
    return jdbc.sql(
            "WITH d AS (DELETE FROM payment.razorpay_refund_parked WHERE provider_refund_id = :r "
                + "RETURNING status, provider_payment_id, amount_minor, currency, received_at) "
                + "SELECT status, provider_payment_id, amount_minor, currency FROM d "
                + "ORDER BY received_at")
        .param("r", providerRefundId)
        .query(
            (rs, n) ->
                new ParkedRefundEvent(
                    rs.getString("status"),
                    rs.getString("provider_payment_id"),
                    rs.getLong("amount_minor"),
                    rs.getString("currency")))
        .list();
  }

  public boolean eventProcessed(String eventId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM payment.processed_events WHERE event_id = :e)")
        .param("e", eventId)
        .query(Boolean.class)
        .single();
  }

  /** True when the event was new (first time seen). */
  public boolean recordEvent(String eventId) {
    return jdbc.sql(
                "INSERT INTO payment.processed_events (event_id) VALUES (:e) "
                    + "ON CONFLICT DO NOTHING")
            .param("e", eventId)
            .update()
        > 0;
  }
}
