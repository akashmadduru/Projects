package com.acme.ecommerce.promotion.repository;

import com.acme.ecommerce.promotion.api.dto.CouponRequest;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Coupon + redemption persistence on plain SQL. */
@Repository
public class CouponRepository {

  /** Coupon row; {@code redeemed} = APPLIED redemptions. */
  public record Coupon(
      UUID id,
      String code,
      String description,
      String type,
      BigDecimal value,
      BigDecimal maxDiscount,
      BigDecimal minOrderAmount,
      String currency,
      Instant startsAt,
      Instant endsAt,
      Integer totalLimit,
      Integer perUserLimit,
      boolean firstOrderOnly,
      boolean active,
      long redeemed,
      Instant createdAt,
      Instant updatedAt) {}

  /** Redemption row. */
  public record Redemption(UUID id, UUID couponId, String status, BigDecimal discountAmount) {}

  private static final String SELECT =
      "SELECT c.*, (SELECT count(*) FROM promotion.redemptions r WHERE r.coupon_id = c.id "
          + "AND r.status = 'APPLIED') AS redeemed FROM promotion.coupons c ";

  private final JdbcClient jdbc;

  public CouponRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static Instant instant(ResultSet rs, String col) throws SQLException {
    OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  private static Integer integer(ResultSet rs, String col) throws SQLException {
    int v = rs.getInt(col);
    return rs.wasNull() ? null : v;
  }

  private static Coupon map(ResultSet rs, int n) throws SQLException {
    String currency = rs.getString("currency");
    return new Coupon(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("description"),
        rs.getString("type"),
        rs.getBigDecimal("value"),
        rs.getBigDecimal("max_discount"),
        rs.getBigDecimal("min_order_amount"),
        currency == null ? null : currency.trim(),
        instant(rs, "starts_at"),
        instant(rs, "ends_at"),
        integer(rs, "total_limit"),
        integer(rs, "per_user_limit"),
        rs.getBoolean("first_order_only"),
        rs.getBoolean("active"),
        rs.getLong("redeemed"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  public Optional<Coupon> findByCode(String code) {
    return jdbc.sql(SELECT + "WHERE c.code = :c")
        .param("c", code)
        .query(CouponRepository::map)
        .optional();
  }

  public Optional<Coupon> findById(UUID id) {
    return jdbc.sql(SELECT + "WHERE c.id = :i")
        .param("i", id)
        .query(CouponRepository::map)
        .optional();
  }

  /** Serialises redemptions of one coupon (limit checks). */
  public void lock(UUID couponId) {
    jdbc.sql("SELECT id FROM promotion.coupons WHERE id = :i FOR UPDATE")
        .param("i", couponId)
        .query(UUID.class)
        .optional();
  }

  public List<Coupon> findAll() {
    return jdbc.sql(SELECT + "ORDER BY c.created_at DESC LIMIT 500")
        .query(CouponRepository::map)
        .list();
  }

  public List<Coupon> findActive() {
    return jdbc.sql(SELECT + "WHERE c.active = true ORDER BY c.code")
        .query(CouponRepository::map)
        .list();
  }

  public long userRedemptions(UUID couponId, UUID userId) {
    return jdbc.sql(
            "SELECT count(*) FROM promotion.redemptions WHERE coupon_id = :c AND user_id = :u "
                + "AND status = 'APPLIED'")
        .param("c", couponId)
        .param("u", userId)
        .query(Long.class)
        .single();
  }

  public UUID insert(CouponRequest r, String code) {
    return jdbc.sql(
            "INSERT INTO promotion.coupons (code, description, type, value, max_discount, "
                + "min_order_amount, currency, starts_at, ends_at, total_limit, per_user_limit, "
                + "first_order_only, active) VALUES (:code, :d, :t, :v, :max, :min, :cur, :s, "
                + ":e, :tl, :ul, :fo, :a) RETURNING id")
        .param("code", code)
        .param("d", r.description())
        .param("t", r.type())
        .param("v", r.value())
        .param("max", r.maxDiscount())
        .param("min", r.minOrderAmount() == null ? BigDecimal.ZERO : r.minOrderAmount())
        .param("cur", r.currency() == null ? null : r.currency().toUpperCase())
        .param(
            "s",
            r.startsAt() == null
                ? null
                : OffsetDateTime.ofInstant(r.startsAt(), java.time.ZoneOffset.UTC))
        .param(
            "e",
            r.endsAt() == null
                ? null
                : OffsetDateTime.ofInstant(r.endsAt(), java.time.ZoneOffset.UTC))
        .param("tl", r.totalLimit())
        .param("ul", r.perUserLimit())
        .param("fo", Boolean.TRUE.equals(r.firstOrderOnly()))
        .param("a", r.active() == null || r.active())
        .query(UUID.class)
        .single();
  }

  public int update(UUID id, CouponRequest r) {
    return jdbc.sql(
            "UPDATE promotion.coupons SET description = :d, type = :t, value = :v, "
                + "max_discount = :max, min_order_amount = :min, currency = :cur, "
                + "starts_at = :s, ends_at = :e, total_limit = :tl, per_user_limit = :ul, "
                + "first_order_only = :fo, active = :a, updated_at = now() WHERE id = :i")
        .param("i", id)
        .param("d", r.description())
        .param("t", r.type())
        .param("v", r.value())
        .param("max", r.maxDiscount())
        .param("min", r.minOrderAmount() == null ? BigDecimal.ZERO : r.minOrderAmount())
        .param("cur", r.currency() == null ? null : r.currency().toUpperCase())
        .param(
            "s",
            r.startsAt() == null
                ? null
                : OffsetDateTime.ofInstant(r.startsAt(), java.time.ZoneOffset.UTC))
        .param(
            "e",
            r.endsAt() == null
                ? null
                : OffsetDateTime.ofInstant(r.endsAt(), java.time.ZoneOffset.UTC))
        .param("tl", r.totalLimit())
        .param("ul", r.perUserLimit())
        .param("fo", Boolean.TRUE.equals(r.firstOrderOnly()))
        .param("a", r.active() == null || r.active())
        .update();
  }

  public int deactivate(UUID id) {
    return jdbc.sql("UPDATE promotion.coupons SET active = false, updated_at = now() WHERE id = :i")
        .param("i", id)
        .update();
  }

  public Optional<Redemption> findRedemption(UUID orderId) {
    return jdbc.sql(
            "SELECT id, coupon_id, status, discount_amount FROM promotion.redemptions "
                + "WHERE order_id = :o")
        .param("o", orderId)
        .query(
            (rs, n) ->
                new Redemption(
                    rs.getObject("id", UUID.class),
                    rs.getObject("coupon_id", UUID.class),
                    rs.getString("status"),
                    rs.getBigDecimal("discount_amount")))
        .optional();
  }

  public void insertRedemption(UUID couponId, UUID userId, UUID orderId, BigDecimal discount) {
    jdbc.sql(
            "INSERT INTO promotion.redemptions (coupon_id, user_id, order_id, discount_amount, "
                + "status) VALUES (:c, :u, :o, :d, 'APPLIED')")
        .param("c", couponId)
        .param("u", userId)
        .param("o", orderId)
        .param("d", discount)
        .update();
  }

  public void reapplyRedemption(UUID id, UUID couponId, UUID userId, BigDecimal discount) {
    jdbc.sql(
            "UPDATE promotion.redemptions SET status = 'APPLIED', coupon_id = :c, user_id = :u, "
                + "discount_amount = :d WHERE id = :i")
        .param("i", id)
        .param("c", couponId)
        .param("u", userId)
        .param("d", discount)
        .update();
  }

  public int release(UUID orderId) {
    return jdbc.sql(
            "UPDATE promotion.redemptions SET status = 'RELEASED' WHERE order_id = :o "
                + "AND status = 'APPLIED'")
        .param("o", orderId)
        .update();
  }
}
