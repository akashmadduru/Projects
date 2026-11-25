package com.acme.ecommerce.product.search;

import com.acme.ecommerce.product.api.dto.ProductResponse;
import com.acme.ecommerce.product.embedding.EmbeddingProperties;
import com.acme.ecommerce.product.embedding.QueryEmbeddingService;
import com.acme.ecommerce.product.search.HybridSearchRepository.Fused;
import com.acme.ecommerce.product.service.ProductCatalogService;
import com.acme.ecommerce.product.service.ProductDiscoveryService;
import com.acme.ecommerce.product.web.dto.FacetsResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Natural-language product search: keyword + semantic retrieval fused by Reciprocal Rank Fusion.
 *
 * <p>THE ORDER OF OPERATIONS IS THE DESIGN. (1) Normalise the query. (2) Infer obvious filters. (3)
 * Embed the query - cache, then provider behind a breaker - with NO transaction open, because the
 * call can take up to the query timeout and the connection pool is small. (4) Only then open one
 * short read-only transaction for the single ranking statement. (5) Hydrate the requested page.
 *
 * <p>DEGRADATION. If the caller asked for semantic or hybrid but there is no vector (feature
 * disabled, provider down, breaker open, timeout), the SAME statement runs with the vector leg
 * removed and the response says {@code effectiveMode=keyword, degraded=true}. It is never an error
 * and never fake data. A pure {@code mode=keyword} request is not "degraded".
 *
 * <p>THE CEILING. Each leg contributes at most {@code topN} ids, so a result - and its {@code
 * totalElements} - holds at most {@code 2 * topN} products. Paging and facets work inside that set.
 * Deep paging is not a goal of search.
 */
@Service
public class HybridSearchService {

  /** The retrieval outcome shared by the list and the facets. */
  private record Retrieval(
      List<Fused> fused,
      SearchMode effectiveMode,
      boolean degraded,
      InterpretedFilters interpreted) {}

  private final HybridSearchRepository repository;
  private final QueryEmbeddingService queryEmbeddings;
  private final QueryUnderstanding understanding;
  private final ProductCatalogService catalog;
  private final ProductDiscoveryService discovery;
  private final EmbeddingProperties properties;
  private final MeterRegistry meters;
  private final TransactionTemplate readOnlyTx;

  public HybridSearchService(
      HybridSearchRepository repository,
      QueryEmbeddingService queryEmbeddings,
      QueryUnderstanding understanding,
      ProductCatalogService catalog,
      ProductDiscoveryService discovery,
      EmbeddingProperties properties,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.queryEmbeddings = queryEmbeddings;
    this.understanding = understanding;
    this.catalog = catalog;
    this.discovery = discovery;
    this.properties = properties;
    this.meters = meters;
    this.readOnlyTx = new TransactionTemplate(transactionManager);
    this.readOnlyTx.setReadOnly(true);
  }

  /** One page of search results. */
  public SearchResponse search(SearchQuery query) {
    Retrieval retrieval = retrieve(query);
    List<Fused> fused = retrieval.fused();
    int total = fused.size();
    int from = (int) Math.min((long) query.page() * query.size(), total);
    int to = Math.min(from + query.size(), total);
    List<Fused> pageRows = fused.subList(from, to);

    List<ProductResponse> content = catalog.findByIds(pageRows.stream().map(Fused::id).toList());
    Map<UUID, String> matchedBy = new LinkedHashMap<>();
    for (Fused row : pageRows) {
      matchedBy.put(row.id(), matchedBy(row));
    }
    int totalPages = (int) Math.ceil(total / (double) query.size());
    return new SearchResponse(
        content,
        new SearchResponse.PageInfo(query.size(), query.page(), total, totalPages),
        retrieval.effectiveMode().wire(),
        retrieval.degraded(),
        retrieval.interpreted(),
        matchedBy);
  }

  /**
   * Facets over exactly the candidate set {@link #search} pages through, except the brand facet:
   * like the catalog facets it drops its own filter, so it counts the candidates retrieved without
   * the chosen brands (one more retrieval; the query vector is cached).
   */
  public FacetsResponse facets(SearchQuery query) {
    FacetsResponse full = discovery.facetsForIds(ids(retrieve(query)));
    SearchFilters f = query.filters();
    if (f.brands() == null || f.brands().isEmpty()) {
      return full;
    }
    SearchQuery unbranded =
        new SearchQuery(
            query.text(),
            query.mode(),
            query.sort(),
            new SearchFilters(f.categoryId(), f.minPrice(), f.maxPrice(), f.minRating(), List.of()),
            query.page(),
            query.size());
    FacetsResponse others = discovery.facetsForIds(ids(retrieve(unbranded)));
    return new FacetsResponse(others.brands(), full.attributes(), full.price());
  }

  private static List<UUID> ids(Retrieval retrieval) {
    return retrieval.fused().stream().map(Fused::id).toList();
  }

  private Retrieval retrieve(SearchQuery query) {
    String normalized = normalize(query.text());
    InterpretedFilters interpreted = understanding.interpret(normalized, query.filters());
    SearchFilters effective = merge(query.filters(), interpreted);

    Optional<float[]> vector = Optional.empty();
    if (query.mode() != SearchMode.KEYWORD) {
      vector = queryEmbeddings.embed(normalized);
    }
    boolean degraded = query.mode() != SearchMode.KEYWORD && vector.isEmpty();
    SearchMode effectiveMode = vector.isPresent() ? query.mode() : SearchMode.KEYWORD;

    EmbeddingProperties.Search cfg = properties.getSearch();
    HybridSearchRepository.FuseRequest request =
        new HybridSearchRepository.FuseRequest(
            normalized,
            effectiveMode != SearchMode.SEMANTIC,
            vector.orElse(null),
            queryEmbeddings.modelId().orElse(null),
            effective,
            query.sort(),
            cfg.getRrfK(),
            cfg.getTopN(),
            cfg.getMaxDistance(),
            cfg.getTrigramThreshold(),
            cfg.getHnswEfSearch());
    List<Fused> fused = readOnlyTx.execute(status -> repository.fuse(request));

    meters
        .counter(
            "search.requests",
            "requested",
            query.mode().wire(),
            "effective",
            effectiveMode.wire(),
            "degraded",
            Boolean.toString(degraded))
        .increment();
    return new Retrieval(fused == null ? List.of() : fused, effectiveMode, degraded, interpreted);
  }

  /**
   * Explicit filters win; inferred ones only fill the gaps (already guaranteed by understanding).
   */
  private static SearchFilters merge(SearchFilters explicit, InterpretedFilters interpreted) {
    BigDecimal minPrice =
        explicit.minPrice() != null ? explicit.minPrice() : interpreted.minPrice();
    BigDecimal maxPrice =
        explicit.maxPrice() != null ? explicit.maxPrice() : interpreted.maxPrice();
    BigDecimal minRating =
        explicit.minRating() != null ? explicit.minRating() : interpreted.minRating();
    List<String> brands =
        explicit.brands() != null && !explicit.brands().isEmpty()
            ? explicit.brands()
            : interpreted.brand() == null ? List.of() : List.of(interpreted.brand());
    return new SearchFilters(explicit.categoryId(), minPrice, maxPrice, minRating, brands);
  }

  /** {@code lower(trim(collapse-whitespace))}: the cache key and the text every leg sees. */
  static String normalize(String text) {
    return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  private static String matchedBy(Fused row) {
    if (row.inKeyword() && row.inVector()) {
      return "both";
    }
    return row.inVector() ? "semantic" : "keyword";
  }
}
