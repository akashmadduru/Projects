package com.acme.ecommerce.product.service;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import com.acme.ecommerce.product.domain.Category;
import com.acme.ecommerce.product.domain.Product;
import com.acme.ecommerce.product.repository.CategoryRepository;
import com.acme.ecommerce.product.repository.ProductFilter;
import com.acme.ecommerce.product.repository.ProductNativeQueries;
import com.acme.ecommerce.product.repository.ProductRepository;
import com.acme.ecommerce.product.repository.ProductSpecifications;
import com.acme.ecommerce.product.web.dto.FacetsResponse;
import com.acme.ecommerce.product.web.dto.SuggestResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Search suggestions, facets, product views / recommendations and sitemap data. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductDiscoveryService {

  private static final int VIEW_RETENTION_DAYS = 30;

  private final ProductRepository products;
  private final CategoryRepository categories;
  private final ProductNativeQueries natives;
  private final ProductCatalogService catalog;
  private final ObjectMapper objectMapper;

  @Transactional(readOnly = true)
  public SuggestResponse suggest(String q, int limit) {
    String query = q.trim();
    List<UUID> ids = natives.rankedIds(query);
    List<SuggestResponse.Item> items = new ArrayList<>();
    if (!ids.isEmpty()) {
      Map<UUID, Product> byId = new LinkedHashMap<>();
      products.findAllById(ids).forEach(p -> byId.put(p.getId(), p));
      for (UUID id : ids) {
        Product p = byId.get(id);
        if (p != null && p.isActive()) {
          items.add(
              new SuggestResponse.Item(
                  p.getId(),
                  p.getSlug(),
                  p.getName(),
                  p.getThumbnailUrl(),
                  p.getPriceAmount(),
                  p.getSalePrice(),
                  p.getPriceCurrency()));
          if (items.size() >= limit) {
            break;
          }
        }
      }
    }
    List<SuggestResponse.Cat> cats = new ArrayList<>();
    for (Object[] row : natives.categoriesMatching(query, 5)) {
      cats.add(new SuggestResponse.Cat(UUID.fromString(row[0].toString()), row[1].toString()));
    }
    String didYouMean = items.isEmpty() ? didYouMean(query) : null;
    return new SuggestResponse(query, items, cats, didYouMean);
  }

  private String didYouMean(String query) {
    String[] words = query.toLowerCase().split("\\s+");
    List<String> out = new ArrayList<>();
    boolean changed = false;
    for (String w : words) {
      String best = w.length() > 2 ? natives.closestWord(w) : null;
      if (best != null && !best.equals(w)) {
        changed = true;
        out.add(best);
      } else {
        out.add(w);
      }
    }
    return changed ? String.join(" ", out) : null;
  }

  /** Facets over the full filtered set (not excluding each facet's own filter - tech debt). */
  @Transactional(readOnly = true)
  public FacetsResponse facets(ProductFilter filter) {
    boolean hasQuery = filter.query() != null && !filter.query().isBlank();
    List<UUID> ranked = hasQuery ? natives.rankedIds(filter.query()) : null;
    List<Product> set = products.findAll(ProductSpecifications.matching(filter, ranked));
    Map<String, Long> brandCounts = new LinkedHashMap<>();
    Map<String, Map<String, Long>> attrCounts = new LinkedHashMap<>();
    BigDecimal min = null;
    BigDecimal max = null;
    for (Product p : set) {
      if (p.getBrand() != null && !p.getBrand().isBlank()) {
        brandCounts.merge(p.getBrand(), 1L, Long::sum);
      }
      if (p.getAttributes() != null) {
        try {
          Map<String, Object> attrs =
              objectMapper.readValue(p.getAttributes(), new TypeReference<>() {});
          attrs.forEach(
              (k, v) ->
                  attrCounts
                      .computeIfAbsent(k, x -> new LinkedHashMap<>())
                      .merge(String.valueOf(v), 1L, Long::sum));
        } catch (Exception e) {
          log.debug("Skipping unreadable attributes for {}", p.getId());
        }
      }
      BigDecimal price = p.getPriceAmount();
      if (price != null) {
        min = min == null || price.compareTo(min) < 0 ? price : min;
        max = max == null || price.compareTo(max) > 0 ? price : max;
      }
    }
    List<FacetsResponse.Value> brands = toValues(brandCounts);
    List<FacetsResponse.Attribute> attributes =
        attrCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> new FacetsResponse.Attribute(e.getKey(), toValues(e.getValue())))
            .toList();
    return new FacetsResponse(brands, attributes, new FacetsResponse.PriceRange(min, max));
  }

  private static List<FacetsResponse.Value> toValues(Map<String, Long> counts) {
    return counts.entrySet().stream()
        .map(e -> new FacetsResponse.Value(e.getKey(), e.getValue()))
        .sorted(
            Comparator.comparingLong(FacetsResponse.Value::count)
                .reversed()
                .thenComparing(FacetsResponse.Value::value))
        .toList();
  }

  @Transactional
  public void recordView(UUID productId, String sessionId) {
    if (!products.existsById(productId)) {
      throw new ResourceNotFoundException("Product", productId);
    }
    natives.recordView(sessionId, productId);
  }

  @Transactional(readOnly = true)
  public List<ProductResponse> alsoViewed(UUID productId, int limit) {
    if (!products.existsById(productId)) {
      throw new ResourceNotFoundException("Product", productId);
    }
    List<UUID> ids = new ArrayList<>(natives.alsoViewedIds(productId, limit));
    if (ids.size() < limit) {
      ids.addAll(natives.sameCategoryIds(productId, ids, limit - ids.size()));
    }
    return catalog.findByIds(ids);
  }

  /** All active product slugs. */
  @Transactional(readOnly = true)
  public List<String> activeSlugs() {
    return products.findAll().stream()
        .filter(Product::isActive)
        .map(Product::getSlug)
        .filter(s -> s != null && !s.isBlank())
        .sorted()
        .toList();
  }

  @Transactional(readOnly = true)
  public List<UUID> activeCategoryIds() {
    return categories.findAll().stream()
        .filter(Category::isActive)
        .map(Category::getId)
        .toList();
  }

  @Scheduled(cron = "0 17 3 * * *")
  @Transactional
  public void purgeOldViews() {
    int n = natives.deleteViewsOlderThanDays(VIEW_RETENTION_DAYS);
    log.info("Purged {} product views older than {} days", n, VIEW_RETENTION_DAYS);
  }
}
