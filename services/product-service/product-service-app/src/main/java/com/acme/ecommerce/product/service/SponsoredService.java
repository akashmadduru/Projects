package com.acme.ecommerce.product.service;

import com.acme.ecommerce.product.api.dto.ProductResponse;
import com.acme.ecommerce.product.client.SellerFeignClient.AdRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Prepends active sponsored placements (seller-service campaigns) to the first page of a list. */
@Service
public class SponsoredService {

  private static final int MAX_SPONSORED = 2;

  private final SellerDirectory sellers;
  private final ProductCatalogService catalog;

  public SponsoredService(SellerDirectory sellers, ProductCatalogService catalog) {
    this.sellers = sellers;
    this.catalog = catalog;
  }

  public List<ProductResponse> inject(List<ProductResponse> organic, String q, UUID categoryId) {
    List<AdRef> ads = sellers.activeAds(q, categoryId, MAX_SPONSORED);
    if (ads.isEmpty()) {
      return organic;
    }
    Map<UUID, ProductResponse> products = new HashMap<>();
    catalog.findByIds(ads.stream().map(AdRef::productId).distinct().toList())
        .forEach(p -> products.put(p.getId(), p));
    List<ProductResponse> out = new ArrayList<>();
    Set<UUID> used = new HashSet<>();
    for (AdRef ad : ads) {
      ProductResponse p = products.get(ad.productId());
      if (p != null && used.add(p.getId()) && out.size() < MAX_SPONSORED) {
        p.setSponsored(true);
        p.setAdId(ad.adId());
        out.add(p);
      }
    }
    organic.stream().filter(p -> !used.contains(p.getId())).forEach(out::add);
    return out;
  }
}
