package com.acme.ecommerce.payment.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only queries for the admin console. Kept apart from {@link PaymentRepository} so the
 * write-path class stays small. Filters are allow-listed and always bound as parameters; the only
 * concatenated SQL is from fixed fragments in this file.
 */
@Repository
public class AdminPaymentQueries {

  public static final Set<String> STATUSES =
      Set.of(
          "REQUIRES_PAYMENT",
          "PROCESSING",
          "SUCCEEDED",
          "FAILED",
          "REFUNDED",
          "PARTIALLY_REFUNDED",
          "COD_PENDING");
  public static final Set<String> METHODS = Set.of("CARD", "COD");

  public record AdminPaymentRow(
      UUID id,
      UUID orderId,
      UUID userId,
      String method,
      String mode,
      BigDecimal amount,
      String currency,
      String status,
      BigDecimal refundedAmount,
      Instant createdAt,
      Instant updatedAt) {}

  public record RefundRow(UUID id, BigDecimal amount, String reason, Instant createdAt) {}

  private static final String COLS =
      "id, order_id, user_id, method, mode, amount, currency, status, refunded_amount,"
          + " created_at, updated_at";

  private final JdbcClient jdbc;

  public AdminPaymentQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<AdminPaymentRow> search(
      String status, String method, UUID orderId, int limit, long offset) {
    Filter f = filter(status, method, orderId);
    JdbcClient.StatementSpec spec =
        jdbc.sql(
            "SELECT "
                + COLS
                + " FROM payment.payments"
                + f.where
                + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset");
    f.params.forEach(spec::param);
    return spec.param("limit", limit)
        .param("offset", offset)
        .query(AdminPaymentQueries::map)
        .list();
  }

  public long count(String status, String method, UUID orderId) {
    Filter f = filter(status, method, orderId);
    JdbcClient.StatementSpec spec = jdbc.sql("SELECT count(*) FROM payment.payments" + f.where);
    f.params.forEach(spec::param);
    return spec.query(Long.class).single();
  }

  public java.util.Optional<AdminPaymentRow> findById(UUID id) {
    return jdbc.sql("SELECT " + COLS + " FROM payment.payments WHERE id = :i")
        .param("i", id)
        .query(AdminPaymentQueries::map)
        .optional();
  }

  public List<RefundRow> refunds(UUID paymentId) {
    return jdbc.sql(
            "SELECT id, amount, reason, created_at FROM payment.refunds"
                + " WHERE payment_id = :p ORDER BY created_at")
        .param("p", paymentId)
        .query(
            (rs, n) ->
                new RefundRow(
                    rs.getObject("id", UUID.class),
                    rs.getBigDecimal("amount"),
                    rs.getString("reason"),
                    instant(rs, "created_at")))
        .list();
  }

  /** Rows grouped by status for the dashboard. */
  public Map<String, Long> countByStatus() {
    Map<String, Long> out = new LinkedHashMap<>();
    STATUSES.stream().sorted().forEach(s -> out.put(s, 0L));
    jdbc.sql("SELECT status, count(*) AS n FROM payment.payments GROUP BY status")
        .query((rs, n) -> Map.entry(rs.getString("status"), rs.getLong("n")))
        .list()
        .forEach(e -> out.put(e.getKey(), e.getValue()));
    return out;
  }

  private record Filter(String where, Map<String, Object> params) {}

  private static Filter filter(String status, String method, UUID orderId) {
    List<String> clauses = new ArrayList<>();
    Map<String, Object> params = new LinkedHashMap<>();
    if (status != null && STATUSES.contains(status)) {
      clauses.add("status = :status");
      params.put("status", status);
    }
    if (method != null && METHODS.contains(method)) {
      clauses.add("method = :method");
      params.put("method", method);
    }
    if (orderId != null) {
      clauses.add("order_id = :orderId");
      params.put("orderId", orderId);
    }
    String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    return new Filter(where, params);
  }

  private static AdminPaymentRow map(ResultSet rs, int n) throws SQLException {
    return new AdminPaymentRow(
        rs.getObject("id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("method"),
        rs.getString("mode"),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        rs.getString("status"),
        rs.getBigDecimal("refunded_amount"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Instant instant(ResultSet rs, String col) throws SQLException {
    OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }
}
