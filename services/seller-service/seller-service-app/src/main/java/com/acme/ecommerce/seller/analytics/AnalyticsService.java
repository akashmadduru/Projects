package com.acme.ecommerce.seller.analytics;

import com.acme.ecommerce.seller.client.ProductFeignClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Seller dashboard numbers, derived from the settlement ledger + sale lines. */
@Service
public class AnalyticsService {

  private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

  public record TopProduct(UUID productId, String name, long units, BigDecimal revenue) {}

  public record Daily(LocalDate date, BigDecimal revenue, long orders) {}

  public record Analytics(
      BigDecimal revenue, long orders, long unitsSold, long returns, double returnRate, BigDecimal avgRating,
      List<TopProduct> topProducts, List<Daily> daily, long views, double conversionRate) {}

  private final JdbcClient jdbc;
  private final ProductFeignClient products;

  public AnalyticsService(JdbcClient jdbc, ProductFeignClient products) {
    this.jdbc = jdbc;
    this.products = products;
  }

  @Transactional(readOnly = true)
  public Analytics compute(UUID sellerId, LocalDate from, LocalDate to) {
    LocalDate t = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
    LocalDate f = from == null ? t.minusDays(29) : from;
    var start = f.atStartOfDay().atOffset(ZoneOffset.UTC);
    var end = t.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

    BigDecimal revenue = jdbc.sql("SELECT COALESCE(sum(amount),0) FROM ledger_entries WHERE seller_id=:s AND type='SALE' AND created_at >= :a AND created_at < :b")
        .param("s", sellerId).param("a", start).param("b", end).query(BigDecimal.class).single();
    long orders = count(sellerId, "SALE", start, end);
    long returns = count(sellerId, "REFUND_REVERSAL", start, end);
    long units = jdbc.sql("SELECT COALESCE(sum(quantity),0) FROM sale_lines WHERE seller_id=:s AND created_at >= :a AND created_at < :b")
        .param("s", sellerId).param("a", start).param("b", end).query(Long.class).single();
    BigDecimal rating = jdbc.sql("SELECT rating_avg FROM sellers WHERE id=:s").param("s", sellerId).query(BigDecimal.class).single();
    List<TopProduct> top = jdbc.sql(
            "SELECT product_id, max(name) n, sum(quantity) u, sum(amount) r FROM sale_lines WHERE seller_id=:s AND created_at >= :a AND created_at < :b"
                + " GROUP BY product_id ORDER BY r DESC LIMIT 5")
        .param("s", sellerId).param("a", start).param("b", end)
        .query((rs, n) -> new TopProduct(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getBigDecimal(4)))
        .list();
    List<Daily> daily = jdbc.sql(
            "SELECT (created_at AT TIME ZONE 'UTC')::date d, sum(amount) r, count(*) c FROM ledger_entries WHERE seller_id=:s AND type='SALE'"
                + " AND created_at >= :a AND created_at < :b GROUP BY d ORDER BY d")
        .param("s", sellerId).param("a", start).param("b", end)
        .query((rs, n) -> new Daily(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2), rs.getLong(3)))
        .list();
    long views = views(sellerId, f, t);
    double returnRate = orders == 0 ? 0 : BigDecimal.valueOf(returns).divide(BigDecimal.valueOf(orders), 4, RoundingMode.HALF_UP).doubleValue();
    double conv = views == 0 ? 0 : BigDecimal.valueOf(orders).divide(BigDecimal.valueOf(views), 4, RoundingMode.HALF_UP).doubleValue();
    return new Analytics(revenue, orders, units, returns, returnRate, rating, top, daily, views, conv);
  }

  private long count(UUID sellerId, String type, java.time.OffsetDateTime a, java.time.OffsetDateTime b) {
    return jdbc.sql("SELECT count(*) FROM ledger_entries WHERE seller_id=:s AND type=:t AND created_at >= :a AND created_at < :b")
        .param("s", sellerId).param("t", type).param("a", a).param("b", b).query(Long.class).single();
  }

  private long views(UUID sellerId, LocalDate from, LocalDate to) {
    try {
      Object o = products.views(sellerId, from.toString(), to.toString());
      if (o instanceof Number n) {
        return n.longValue();
      }
      if (o instanceof Map<?, ?> m && m.get("views") instanceof Number n) {
        return n.longValue();
      }
    } catch (RuntimeException e) {
      log.debug("views unavailable: {}", e.toString());
    }
    return 0;
  }
}
