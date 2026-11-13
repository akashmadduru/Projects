package com.acme.ecommerce.product.web;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.product.domain.Offer;
import com.acme.ecommerce.product.service.OfferService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service reads (X-Internal-Token); never routed by the gateway. */
@RestController
public class InternalCatalogController {

  public record OfferRef(UUID offerId, UUID productId, UUID variantId, UUID sellerId, boolean active) {}

  private final OfferService offers;
  private final JdbcClient jdbc;

  public InternalCatalogController(OfferService offers, JdbcClient jdbc) {
    this.offers = offers;
    this.jdbc = jdbc;
  }

  @GetMapping("/internal/offers/{id}")
  public OfferRef offer(@PathVariable UUID id) {
    Offer o = offers.get(id);
    return new OfferRef(o.getId(), o.getProductId(), o.getVariantId(), o.getSellerId(), o.isActive());
  }

  /** Inventory calls this after seller stock changes so the list-row price follows the buy box. */
  @PostMapping("/internal/products/{id}/sync-prices")
  public void syncPrices(@PathVariable UUID id) {
    offers.syncPrices(id);
  }

  private static LocalDate day(String v, LocalDate fallback) {
    if (v == null || v.isBlank()) {
      return fallback;
    }
    return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
  }

  /** Product page views, per day window, for the products this seller has active offers on. */
  @GetMapping("/internal/products/views/by-seller/{sellerId}")
  public Map<String, Object> viewsBySeller(
      @PathVariable UUID sellerId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    Instant start = day(from, LocalDate.now().minusDays(30)).atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant end = day(to, LocalDate.now()).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    Long views =
        jdbc.sql(
                "SELECT count(*) FROM product.product_views v WHERE v.viewed_at >= :s AND v.viewed_at < :e"
                    + " AND EXISTS (SELECT 1 FROM product.offers o WHERE o.product_id = v.product_id"
                    + " AND o.seller_id = :sid AND o.active)")
            .param("s", java.sql.Timestamp.from(start))
            .param("e", java.sql.Timestamp.from(end))
            .param("sid", sellerId)
            .query(Long.class)
            .single();
    if (views == null) {
      throw new ResourceNotFoundException("Views", sellerId);
    }
    return Map.of("sellerId", sellerId, "views", views);
  }
}
