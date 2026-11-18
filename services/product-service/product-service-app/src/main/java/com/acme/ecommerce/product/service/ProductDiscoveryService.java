package com.acme.ecommerce.product.service;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.common.web.job.JobLock;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import com.acme.ecommerce.product.domain.Category;
import com.acme.ecommerce.product.domain.Product;
import com.acme.ecommerce.product.repository.CategoryRepository;
import com.acme.ecommerce.product.repository.ProductFacetQueries;
import com.acme.ecommerce.product.repository.ProductFilter;
import com.acme.ecommerce.product.repository.ProductFilterNativeSql;
import com.acme.ecommerce.product.repository.ProductFilterNativeSql.Omit;
import com.acme.ecommerce.product.repository.ProductNativeQueries;
import com.acme.ecommerce.product.repository.ProductRepository;
import com.acme.ecommerce.product.web.dto.FacetsResponse;
import com.acme.ecommerce.product.web.dto.SuggestResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final ProductFacetQueries facetQueries;
  private final TimeProvider time;

  /** Optional (absent in plain unit tests): resolves image keys against parentUrl. */
  @Autowired(required = false)
  private com.acme.ecommerce.product.imagestore.ImageUrlResolver imageUrls;

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
                  imageUrls == null ? p.getThumbnailUrl() : imageUrls.resolve(p.getThumbnailUrl()),
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

  /** How long a facet answer is reused for the same filter (counts may lag writes by this). */
  static final Duration FACET_CACHE_TTL = Duration.ofSeconds(30);

  private static final int FACET_CACHE_MAX = 500;

  private record CachedFacets(FacetsResponse facets, long expiresAtMillis) {}

  /** LRU + TTL by filter value (records compare by content, so the filter is its own hash key). */
  private final Map<ProductFilter, CachedFacets> facetCache =
      new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<ProductFilter, CachedFacets> eldest) {
          return size() > FACET_CACHE_MAX;
        }
      };

  /**
   * Brand / attribute / price facets for the catalog list. Each facet drops its OWN filter (see
   * {@link ProductFilterNativeSql}), so selecting one brand still shows every other brand's count.
   * Counted in the database; answers are reused for {@link #FACET_CACHE_TTL} per filter.
   */
  @Transactional(readOnly = true)
  public FacetsResponse facets(ProductFilter filter) {
    long now = time.epochMilli();
    synchronized (facetCache) {
      CachedFacets hit = facetCache.get(filter);
      if (hit != null && hit.expiresAtMillis() > now) {
        return hit.facets();
      }
    }
    boolean hasQuery = filter.query() != null && !filter.query().isBlank();
    List<UUID> ranked = hasQuery ? natives.rankedIds(filter.query()) : null;
    FacetsResponse computed = computeFacets(filter, ranked);
    synchronized (facetCache) {
      facetCache.put(filter, new CachedFacets(computed, now + FACET_CACHE_TTL.toMillis()));
    }
    return computed;
  }

  private FacetsResponse computeFacets(ProductFilter filter, List<UUID> ranked) {
    var brandBuckets = facetQueries.brands(ProductFilterNativeSql.of(filter, ranked, Omit.BRANDS));

    Map<String, List<String>> selected =
        filter.attributes() == null ? Map.of() : filter.attributes();
    List<String> selectedKeys =
        selected.entrySet().stream()
            .filter(e -> !e.getValue().isEmpty())
            .map(Map.Entry::getKey)
            .toList();
    List<ProductFacetQueries.Bucket> attrBuckets =
        new ArrayList<>(
            facetQueries.attributes(
                ProductFilterNativeSql.of(filter, ranked, Omit.NONE), null, selectedKeys));
    for (String key : selectedKeys) {
      attrBuckets.addAll(
          facetQueries.attributes(
              ProductFilterNativeSql.of(filter, ranked, Omit.attribute(key)), key, null));
    }
    var price = facetQueries.price(ProductFilterNativeSql.of(filter, ranked, Omit.PRICE));
    return assemble(brandBuckets, attrBuckets, price);
  }

  /**
   * Facets over an explicit id set - the fused candidates of a hybrid search - so the counts always
   * describe exactly the products the search list is drawn from.
   */
  @Transactional(readOnly = true)
  public FacetsResponse facetsForIds(java.util.Collection<UUID> ids) {
    var where = ProductFilterNativeSql.ofIds(ids);
    return assemble(
        facetQueries.brands(where),
        facetQueries.attributes(where, null, null),
        facetQueries.price(where));
  }

  private static FacetsResponse assemble(
      List<ProductFacetQueries.Bucket> brandBuckets,
      List<ProductFacetQueries.Bucket> attrBuckets,
      ProductFacetQueries.Range price) {
    List<FacetsResponse.Value> brands =
        sorted(brandBuckets.stream().map(b -> new FacetsResponse.Value(b.value(), b.count())));
    Map<String, List<FacetsResponse.Value>> byKey = new TreeMap<>();
    for (var b : attrBuckets) {
      byKey
          .computeIfAbsent(b.key(), k -> new ArrayList<>())
          .add(new FacetsResponse.Value(b.value(), b.count()));
    }
    List<FacetsResponse.Attribute> attributes =
        byKey.entrySet().stream()
            .map(e -> new FacetsResponse.Attribute(e.getKey(), sorted(e.getValue().stream())))
            .toList();
    return new FacetsResponse(
        brands, attributes, new FacetsResponse.PriceRange(price.min(), price.max()));
  }

  private static List<FacetsResponse.Value> sorted(Stream<FacetsResponse.Value> values) {
    return values
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
    return categories.findAll().stream().filter(Category::isActive).map(Category::getId).toList();
  }

  /** Cluster-wide guard so only one replica runs this job per tick. */
  @Autowired private JobLock jobLock;

  @Scheduled(cron = "0 17 3 * * *")
  @Transactional
  public void purgeOldViews() {
    jobLock.runExclusive("product-purge-views", this::purgeOldViewsLocked);
  }

  private void purgeOldViewsLocked() {
    int n = natives.deleteViewsOlderThanDays(VIEW_RETENTION_DAYS);
    log.info("Purged {} product views older than {} days", n, VIEW_RETENTION_DAYS);
  }
}
