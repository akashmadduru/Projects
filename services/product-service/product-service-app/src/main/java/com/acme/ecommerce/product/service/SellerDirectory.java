package com.acme.ecommerce.product.service;

import com.acme.ecommerce.common.core.error.UpstreamUnavailableException;
import com.acme.ecommerce.product.client.SellerFeignClient;
import com.acme.ecommerce.product.client.SellerFeignClient.AdRef;
import com.acme.ecommerce.product.client.SellerFeignClient.SellerInfo;
import com.acme.ecommerce.product.client.SellerFeignClient.SellerRef;
import com.acme.ecommerce.product.domain.Offer;
import feign.FeignException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** seller-service lookups with a small TTL cache; degrades to defaults when it is unavailable. */
@Component
public class SellerDirectory {

  private static final Logger log = LoggerFactory.getLogger(SellerDirectory.class);
  private static final long TTL_MS = 5 * 60 * 1000L;

  public static final SellerInfo PLATFORM =
      new SellerInfo(
          Offer.PLATFORM_SELLER_ID,
          "acme-retail",
          "Acme Retail",
          new BigDecimal("4.50"),
          0,
          "APPROVED");

  private record Cached(SellerInfo info, long at) {}

  private final SellerFeignClient client;
  private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

  @Value("${INTERNAL_API_TOKEN:}")
  private String token;

  public SellerDirectory(SellerFeignClient client) {
    this.client = client;
  }

  /** The seller registered for a user, or empty when none; 503 when seller-service is down. */
  public Optional<SellerRef> byUser(String userId) {
    try {
      return Optional.ofNullable(client.byUser(userId, token));
    } catch (FeignException.NotFound e) {
      return Optional.empty();
    } catch (RuntimeException e) {
      throw new UpstreamUnavailableException("seller-service", e);
    }
  }

  public Map<UUID, SellerInfo> info(Collection<UUID> ids) {
    Map<UUID, SellerInfo> out = new HashMap<>();
    List<UUID> missing = new ArrayList<>();
    long now = System.currentTimeMillis();
    for (UUID id : ids) {
      if (Offer.PLATFORM_SELLER_ID.equals(id)) {
        out.put(id, PLATFORM);
        continue;
      }
      Cached c = cache.get(id);
      if (c != null && now - c.at() < TTL_MS) {
        out.put(id, c.info());
      } else {
        missing.add(id);
      }
    }
    if (!missing.isEmpty()) {
      try {
        List<SellerInfo> fetched =
            client.batch(String.join(",", missing.stream().map(UUID::toString).toList()), token);
        if (fetched != null) {
          for (SellerInfo s : fetched) {
            if (s.sellerId() != null) {
              cache.put(s.sellerId(), new Cached(s, now));
              out.put(s.sellerId(), s);
            }
          }
        }
      } catch (RuntimeException e) {
        log.warn("seller-service lookup failed ({}); using defaults", e.toString());
      }
    }
    return out;
  }

  /** Active sponsored placements; empty when seller-service is unavailable. */
  public List<AdRef> activeAds(String q, UUID categoryId, int limit) {
    try {
      List<AdRef> ads = client.activeAds(q, categoryId, limit, token);
      return ads == null ? List.of() : ads;
    } catch (RuntimeException e) {
      log.debug("active ads unavailable: {}", e.toString());
      return List.of();
    }
  }
}
