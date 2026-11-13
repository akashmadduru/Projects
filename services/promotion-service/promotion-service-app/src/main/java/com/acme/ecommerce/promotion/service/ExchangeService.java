package com.acme.ecommerce.promotion.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.promotion.client.ProductFeignClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Exchange programs, quotes (48 h), checkout application and post-delivery verification. */
@Service
public class ExchangeService {

  private static final Logger log = LoggerFactory.getLogger(ExchangeService.class);

  public record Program(
      UUID id, UUID categoryId, String brand, String title, BigDecimal maxValue, String currency) {}

  public record Quote(
      UUID id,
      UUID userId,
      UUID programId,
      String deviceBrand,
      String deviceModel,
      String condition,
      int ageYears,
      BigDecimal quotedValue,
      String currency,
      Instant expiresAt,
      String status,
      UUID orderId) {}

  public record VerifyResult(
      UUID quoteId,
      UUID orderId,
      String status,
      BigDecimal quotedValue,
      BigDecimal finalValue,
      BigDecimal shortfall) {}

  private final JdbcClient jdbc;
  private final ProductFeignClient products;

  public ExchangeService(JdbcClient jdbc, ProductFeignClient products) {
    this.jdbc = jdbc;
    this.products = products;
  }

  private static Program program(ResultSet rs, int n) throws SQLException {
    return new Program(
        rs.getObject("id", UUID.class),
        rs.getObject("category_id", UUID.class),
        rs.getString("brand"),
        rs.getString("title"),
        rs.getBigDecimal("max_value"),
        rs.getString("currency"));
  }

  private static Quote quote(ResultSet rs, int n) throws SQLException {
    Instant exp = rs.getObject("expires_at", OffsetDateTime.class).toInstant();
    String status = rs.getString("status");
    return new Quote(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("program_id", UUID.class),
        rs.getString("device_brand"),
        rs.getString("device_model"),
        rs.getString("device_condition"),
        rs.getInt("age_years"),
        rs.getBigDecimal("quoted_value"),
        rs.getString("currency").trim(),
        exp,
        "QUOTED".equals(status) && exp.isBefore(Instant.now()) ? "EXPIRED" : status,
        rs.getObject("order_id", UUID.class));
  }

  /** Active programs, optionally narrowed to the product's category (best effort). */
  public List<Program> programs(UUID productId) {
    if (productId == null) {
      return jdbc.sql("select * from promotion.exchange_programs where active order by title")
          .query(ExchangeService::program)
          .list();
    }
    UUID categoryId;
    try {
      categoryId = products.product(productId).categoryId();
    } catch (RuntimeException e) {
      log.warn("Could not resolve category of product {}: {}", productId, e.toString());
      return List.of();
    }
    return jdbc.sql(
            "select * from promotion.exchange_programs where active and category_id = :c order by title")
        .param("c", categoryId)
        .query(ExchangeService::program)
        .list();
  }

  public static BigDecimal compute(BigDecimal max, String condition, int ageYears) {
    BigDecimal cond =
        switch (condition) {
          case "WORKING" -> BigDecimal.ONE;
          case "SCREEN_DAMAGED" -> new BigDecimal("0.5");
          default -> new BigDecimal("0.15");
        };
    BigDecimal age =
        BigDecimal.ONE.subtract(new BigDecimal("0.15").multiply(BigDecimal.valueOf(ageYears)));
    if (age.compareTo(new BigDecimal("0.3")) < 0) {
      age = new BigDecimal("0.3");
    }
    return max.multiply(cond).multiply(age).setScale(2, RoundingMode.HALF_UP);
  }

  @Transactional
  public Quote createQuote(
      UUID userId, UUID programId, String brand, String model, String condition, int ageYears) {
    String cond = condition == null ? "" : condition.trim().toUpperCase(Locale.ROOT);
    if (!List.of("WORKING", "SCREEN_DAMAGED", "NOT_WORKING").contains(cond)) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "Unknown device condition");
    }
    Program p =
        jdbc.sql("select * from promotion.exchange_programs where id = :id and active")
            .param("id", programId)
            .query(ExchangeService::program)
            .optional()
            .orElseThrow(
                () -> new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "Exchange program not found"));
    if (p.brand() != null && !p.brand().equalsIgnoreCase(brand.trim())) {
      throw new CodedException(
          ErrorCode.VALIDATION_FAILED, "This program accepts only " + p.brand() + " devices");
    }
    BigDecimal value = compute(p.maxValue(), cond, ageYears);
    UUID id =
        jdbc.sql(
                "insert into promotion.exchange_quotes (user_id, program_id, device_brand, device_model,"
                    + " device_condition, age_years, quoted_value, currency, expires_at) values (:u, :p, :b,"
                    + " :m, :c, :a, :v, :cur, now() + interval '48 hours') returning id")
            .param("u", userId)
            .param("p", programId)
            .param("b", brand.trim())
            .param("m", model.trim())
            .param("c", cond)
            .param("a", ageYears)
            .param("v", value)
            .param("cur", p.currency())
            .query(UUID.class)
            .single();
    return find(id);
  }

  public Quote find(UUID id) {
    return jdbc.sql("select * from promotion.exchange_quotes where id = :id")
        .param("id", id)
        .query(ExchangeService::quote)
        .optional()
        .orElseThrow(() -> new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "Exchange quote not found"));
  }

  /** Owner-checked read for order-service (404 for someone else's quote). */
  public Quote findOwned(UUID id, UUID userId) {
    Quote q = find(id);
    if (!q.userId().equals(userId)) {
      throw new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "Exchange quote not found");
    }
    return q;
  }

  public List<Quote> listByStatus(String status) {
    String s = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
    return jdbc.sql(
            "select * from promotion.exchange_quotes where (cast(:s as text) is null or status = :s)"
                + " order by created_at desc limit 100")
        .param("s", s)
        .query(ExchangeService::quote)
        .list();
  }

  public List<Quote> mine(UUID userId) {
    return jdbc.sql(
            "select * from promotion.exchange_quotes where user_id = :u order by created_at desc limit 50")
        .param("u", userId)
        .query(ExchangeService::quote)
        .list();
  }

  @Transactional
  public void apply(UUID quoteId, UUID userId, UUID orderId) {
    int rows =
        jdbc.sql(
                "update promotion.exchange_quotes set status = 'APPLIED', order_id = :o where id = :id"
                    + " and user_id = :u and expires_at > now() and (status = 'QUOTED' or (status ="
                    + " 'APPLIED' and order_id = :o))")
            .param("o", orderId)
            .param("id", quoteId)
            .param("u", userId)
            .update();
    if (rows == 0) {
      throw new CodedException(
          ErrorCode.VALIDATION_FAILED, "This exchange quote has expired or was already used");
    }
  }

  @Transactional
  public void release(UUID orderId) {
    jdbc.sql(
            "update promotion.exchange_quotes set status = 'QUOTED', order_id = null where order_id = :o"
                + " and status = 'APPLIED'")
        .param("o", orderId)
        .update();
  }

  @Transactional
  public VerifyResult verify(UUID quoteId, boolean accepted, BigDecimal finalValue) {
    Quote q = find(quoteId);
    if (!"APPLIED".equals(q.status())) {
      throw new CodedException(
          ErrorCode.INVALID_STATUS_TRANSITION, "Only an exchange applied to an order can be verified");
    }
    BigDecimal fin =
        accepted
            ? (finalValue == null ? q.quotedValue() : finalValue.min(q.quotedValue()).max(BigDecimal.ZERO))
            : BigDecimal.ZERO;
    BigDecimal shortfall = q.quotedValue().subtract(fin);
    String status = accepted ? "PICKED_UP" : "REJECTED";
    jdbc.sql(
            "update promotion.exchange_quotes set status = :s, final_value = :f, shortfall = :sf,"
                + " verified_at = now() where id = :id")
        .param("s", status)
        .param("f", fin)
        .param("sf", shortfall)
        .param("id", quoteId)
        .update();
    return new VerifyResult(quoteId, q.orderId(), status, q.quotedValue(), fin, shortfall);
  }
}
