package com.acme.ecommerce.seller.ads;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.seller.client.ProductFeignClient;
import java.math.BigDecimal;
import java.sql.Array;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sponsored-product campaigns: seller CRUD, internal active lookup, public click/impression beacons. */
@Service
public class AdService {

  private static final Logger log = LoggerFactory.getLogger(AdService.class);

  public record AdRequest(UUID productId, List<String> keywords, UUID categoryId, BigDecimal bidCpc, BigDecimal dailyBudget, String currency, String status) {}

  public record Campaign(
      UUID id, UUID sellerId, UUID productId, List<String> keywords, UUID categoryId, BigDecimal bidCpc, BigDecimal dailyBudget,
      String currency, String status, BigDecimal spentToday, long clicks, long impressions, OffsetDateTime createdAt) {}

  public record ActiveAd(UUID adId, UUID productId, BigDecimal bid, String currency) {}

  private final JdbcClient jdbc;
  private final ProductFeignClient products;

  public AdService(JdbcClient jdbc, ProductFeignClient products) {
    this.jdbc = jdbc;
    this.products = products;
  }

  private static Campaign map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    Array arr = rs.getArray("keywords");
    List<String> kws = arr == null ? List.of() : List.of((String[]) arr.getArray());
    return new Campaign(rs.getObject("id", UUID.class), rs.getObject("seller_id", UUID.class), rs.getObject("product_id", UUID.class),
        kws, rs.getObject("category_id", UUID.class), rs.getBigDecimal("bid_cpc"), rs.getBigDecimal("daily_budget"),
        rs.getString("currency"), rs.getString("status"), rs.getBigDecimal("spent_today"), rs.getLong("clicks"),
        rs.getLong("impressions"), rs.getObject("created_at", OffsetDateTime.class));
  }

  @Transactional(readOnly = true)
  public List<Campaign> list(UUID sellerId) {
    return jdbc.sql("SELECT * FROM ad_campaigns WHERE seller_id = :s ORDER BY created_at DESC").param("s", sellerId).query(AdService::map).list();
  }

  private static String[] keywords(List<String> in) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    if (in != null) {
      for (String k : in) {
        if (k != null && !k.isBlank()) {
          out.add(k.trim().toLowerCase(Locale.ROOT));
        }
      }
    }
    if (out.size() > 20) {
      throw ValidationException.forField("keywords", "At most 20 keywords");
    }
    return out.toArray(new String[0]);
  }

  private void validate(AdRequest r, boolean create) {
    if (create && r.productId() == null) {
      throw ValidationException.forField("productId", "Required");
    }
    if (r.bidCpc() == null || r.bidCpc().signum() <= 0) {
      throw ValidationException.forField("bidCpc", "Must be positive");
    }
    if (r.dailyBudget() == null || r.dailyBudget().compareTo(r.bidCpc()) < 0) {
      throw ValidationException.forField("dailyBudget", "Must be at least the bid");
    }
  }

  @Transactional
  public Campaign create(UUID sellerId, String sellerStatus, AdRequest r) {
    validate(r, true);
    if (!"APPROVED".equals(sellerStatus)) {
      throw new com.acme.ecommerce.common.core.error.ForbiddenException("Seller account is not approved");
    }
    try {
      products.get(r.productId());
    } catch (feign.FeignException.NotFound e) {
      throw ValidationException.forField("productId", "Unknown product");
    } catch (RuntimeException e) {
      log.debug("Product check skipped: {}", e.toString());
    }
    String[] kws = keywords(r.keywords());
    if (kws.length == 0 && r.categoryId() == null) {
      throw ValidationException.forField("keywords", "Provide keywords or a category");
    }
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO ad_campaigns (id, seller_id, product_id, keywords, category_id, bid_cpc, daily_budget, currency)"
            + " VALUES (:id,:s,:p,:k,:c,:b,:d,:cur)")
        .param("id", id).param("s", sellerId).param("p", r.productId()).param("k", kws).param("c", r.categoryId())
        .param("b", r.bidCpc()).param("d", r.dailyBudget()).param("cur", r.currency() == null ? "USD" : r.currency().toUpperCase(Locale.ROOT))
        .update();
    return get(sellerId, id);
  }

  @Transactional(readOnly = true)
  public Campaign get(UUID sellerId, UUID id) {
    return jdbc.sql("SELECT * FROM ad_campaigns WHERE id = :i AND seller_id = :s").param("i", id).param("s", sellerId)
        .query(AdService::map).optional().orElseThrow(() -> new ResourceNotFoundException("Campaign", id));
  }

  @Transactional
  public Campaign update(UUID sellerId, UUID id, AdRequest r) {
    Campaign c = get(sellerId, id);
    validate(r, false);
    String[] kws = r.keywords() == null ? c.keywords().toArray(new String[0]) : keywords(r.keywords());
    String wanted = r.status() == null ? c.status() : r.status().toUpperCase(Locale.ROOT);
    if (!List.of("ACTIVE", "PAUSED", "EXHAUSTED").contains(wanted)) {
      throw ValidationException.forField("status", "ACTIVE or PAUSED");
    }
    String status = "PAUSED".equals(wanted) ? "PAUSED" : (c.spentToday().compareTo(r.dailyBudget()) >= 0 ? "EXHAUSTED" : "ACTIVE");
    jdbc.sql("UPDATE ad_campaigns SET keywords=:k, category_id=:c, bid_cpc=:b, daily_budget=:d, status=:st WHERE id=:i")
        .param("k", kws).param("c", r.categoryId() != null ? r.categoryId() : c.categoryId()).param("b", r.bidCpc())
        .param("d", r.dailyBudget()).param("st", status).param("i", id)
        .update();
    return get(sellerId, id);
  }

  @Transactional(readOnly = true)
  public List<ActiveAd> active(String q, UUID categoryId, int limit) {
    String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
    if (query.isEmpty() && categoryId == null) {
      return List.of();
    }
    int lim = Math.max(1, Math.min(limit, 10));
    // Highest bid per product first; keyword match = a stored keyword occurs in the query.
    return jdbc.sql(
            "SELECT DISTINCT ON (a.product_id) a.id, a.product_id, a.bid_cpc, a.currency FROM ad_campaigns a JOIN sellers s ON s.id = a.seller_id"
                + " WHERE a.status = 'ACTIVE' AND a.spent_today < a.daily_budget AND s.status = 'APPROVED'"
                + " AND ((:q <> '' AND EXISTS (SELECT 1 FROM unnest(a.keywords) k WHERE position(k in :q) > 0))"
                + "      OR (CAST(:cat AS uuid) IS NOT NULL AND a.category_id = CAST(:cat AS uuid)))"
                + " ORDER BY a.product_id, a.bid_cpc DESC")
        .param("q", query).param("cat", categoryId)
        .query((rs, n) -> new ActiveAd(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBigDecimal(3), rs.getString(4)))
        .list().stream()
        .sorted((x, y) -> y.bid().compareTo(x.bid()))
        .limit(lim)
        .toList();
  }

  @Transactional
  public void click(UUID adId) {
    jdbc.sql("UPDATE ad_campaigns SET clicks = clicks + 1 WHERE id = :i").param("i", adId).update();
    jdbc.sql("UPDATE ad_campaigns SET spent_today = LEAST(spent_today + bid_cpc, daily_budget),"
            + " status = CASE WHEN spent_today + bid_cpc >= daily_budget THEN 'EXHAUSTED' ELSE status END"
            + " WHERE id = :i AND status = 'ACTIVE'")
        .param("i", adId).update();
  }

  @Transactional
  public void impressions(List<UUID> adIds) {
    if (adIds == null || adIds.isEmpty()) {
      return;
    }
    List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(adIds));
    jdbc.sql("UPDATE ad_campaigns SET impressions = impressions + 1 WHERE id IN (:ids)").param("ids", ids.subList(0, Math.min(ids.size(), 50))).update();
  }

  @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
  @Transactional
  public void dailyReset() {
    int n = jdbc.sql("UPDATE ad_campaigns SET spent_today = 0, status = CASE WHEN status = 'EXHAUSTED' THEN 'ACTIVE' ELSE status END WHERE spent_today > 0 OR status = 'EXHAUSTED'").update();
    log.info("Ad budgets reset ({} campaigns)", n);
  }
}
