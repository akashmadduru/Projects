package com.acme.ecommerce.promotion.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Deals, deal claims and bank offers on plain SQL. */
@Repository
public class DealRepository {

  public record Deal(
      UUID id,
      UUID productId,
      UUID variantId,
      String title,
      BigDecimal dealPrice,
      String currency,
      Instant startsAt,
      Instant endsAt,
      int quantityLimit,
      int claimed,
      boolean active,
      Instant membersOnlyUntil) {}

  public record BankOffer(
      UUID id,
      String bank,
      String cardType,
      String title,
      String discountType,
      BigDecimal value,
      BigDecimal maxDiscount,
      BigDecimal minAmount,
      String currency) {}

  private final JdbcClient jdbc;

  public DealRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static Deal map(ResultSet rs, int n) throws SQLException {
    return new Deal(
        rs.getObject("id", UUID.class),
        rs.getObject("product_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("title"),
        rs.getBigDecimal("deal_price"),
        rs.getString("currency"),
        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
        rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
        rs.getInt("quantity_limit"),
        rs.getInt("claimed"),
        rs.getBoolean("active"),
        rs.getObject("members_only_until", OffsetDateTime.class) == null
            ? null
            : rs.getObject("members_only_until", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime odt(Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }

  public List<BankOffer> activeBankOffers() {
    return jdbc.sql(
            "SELECT * FROM promotion.bank_offers WHERE active AND (starts_at IS NULL OR starts_at <= now())"
                + " AND (ends_at IS NULL OR ends_at > now()) ORDER BY bank")
        .query(
            (rs, n) ->
                new BankOffer(
                    rs.getObject("id", UUID.class),
                    rs.getString("bank"),
                    rs.getString("card_type"),
                    rs.getString("title"),
                    rs.getString("discount_type"),
                    rs.getBigDecimal("value"),
                    rs.getBigDecimal("max_discount"),
                    rs.getBigDecimal("min_amount"),
                    rs.getString("currency")))
        .list();
  }

  public List<Deal> findActive(UUID productId) {
    String sql =
        "SELECT * FROM promotion.deals WHERE active AND starts_at <= now() AND ends_at > now()"
            + " AND claimed < quantity_limit"
            + (productId == null ? "" : " AND product_id = :pid")
            + " ORDER BY ends_at";
    JdbcClient.StatementSpec s = jdbc.sql(sql);
    if (productId != null) {
      s = s.param("pid", productId);
    }
    return s.query(DealRepository::map).list();
  }

  public List<Deal> findAll() {
    return jdbc.sql("SELECT * FROM promotion.deals ORDER BY created_at DESC")
        .query(DealRepository::map)
        .list();
  }

  public Optional<Deal> findById(UUID id) {
    return jdbc.sql("SELECT * FROM promotion.deals WHERE id = :id")
        .param("id", id)
        .query(DealRepository::map)
        .optional();
  }

  public Deal insert(Deal d) {
    UUID id =
        jdbc.sql(
                "INSERT INTO promotion.deals (product_id, variant_id, title, deal_price, currency,"
                    + " starts_at, ends_at, quantity_limit, active, members_only_until)"
                    + " VALUES (:p, :v, :t, :price, :c,"
                    + " COALESCE(:s, now()), :e, :q, :a, :mo) RETURNING id")
            .param("p", d.productId())
            .param("v", d.variantId())
            .param("t", d.title())
            .param("price", d.dealPrice())
            .param("c", d.currency().toUpperCase())
            .param("s", odt(d.startsAt()))
            .param("e", odt(d.endsAt()))
            .param("q", d.quantityLimit())
            .param("a", d.active())
            .param("mo", odt(d.membersOnlyUntil()))
            .query(UUID.class)
            .single();
    return findById(id).orElseThrow();
  }

  public Optional<Deal> update(UUID id, Deal d) {
    int rows =
        jdbc.sql(
                "UPDATE promotion.deals SET product_id = :p, variant_id = :v, title = :t,"
                    + " deal_price = :price, currency = :c, starts_at = COALESCE(:s, starts_at),"
                    + " ends_at = :e, quantity_limit = :q, active = :a,"
                    + " members_only_until = :mo WHERE id = :id")
            .param("id", id)
            .param("p", d.productId())
            .param("v", d.variantId())
            .param("t", d.title())
            .param("price", d.dealPrice())
            .param("c", d.currency().toUpperCase())
            .param("s", odt(d.startsAt()))
            .param("e", odt(d.endsAt()))
            .param("q", d.quantityLimit())
            .param("a", d.active())
            .param("mo", odt(d.membersOnlyUntil()))
            .update();
    return rows == 0 ? Optional.empty() : findById(id);
  }

  public Optional<Deal> deactivate(UUID id) {
    int rows =
        jdbc.sql("UPDATE promotion.deals SET active = false WHERE id = :id")
            .param("id", id)
            .update();
    return rows == 0 ? Optional.empty() : findById(id);
  }

  public boolean hasClaims(UUID orderId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM promotion.deal_claims WHERE order_id = :o)")
        .param("o", orderId)
        .query(Boolean.class)
        .single();
  }

  /** Atomic conditional increment; false when fewer than {@code qty} units remain. */
  public boolean tryClaim(UUID dealId, int qty) {
    return jdbc.sql(
                "UPDATE promotion.deals SET claimed = claimed + :q WHERE id = :id AND active"
                    + " AND ends_at > now() AND claimed + :q <= quantity_limit")
            .param("q", qty)
            .param("id", dealId)
            .update()
        == 1;
  }

  public void insertClaim(UUID orderId, UUID dealId, int qty) {
    jdbc.sql("INSERT INTO promotion.deal_claims (order_id, deal_id, quantity) VALUES (:o, :d, :q)")
        .param("o", orderId)
        .param("d", dealId)
        .param("q", qty)
        .update();
  }

  public void releaseClaims(UUID orderId) {
    jdbc.sql(
            "UPDATE promotion.deals d SET claimed = GREATEST(0, d.claimed - c.quantity)"
                + " FROM promotion.deal_claims c WHERE c.deal_id = d.id AND c.order_id = :o"
                + " AND NOT c.released")
        .param("o", orderId)
        .update();
    jdbc.sql("UPDATE promotion.deal_claims SET released = true WHERE order_id = :o")
        .param("o", orderId)
        .update();
  }
}
