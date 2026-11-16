package com.acme.ecommerce.product.search;

import com.acme.ecommerce.product.api.ProductClient;
import com.acme.ecommerce.product.web.dto.FacetsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Natural-language product search (Cartwise AI-1). Public, like every catalog read.
 *
 * <p>Additive: {@code GET /api/products?q=} is untouched and keeps its substring/trigram behaviour.
 * Deliberately thin - bind, validate via {@link SearchParams#toQuery()}, delegate.
 */
@RestController
@RequestMapping(ProductClient.BASE_PATH + "/search")
@RequiredArgsConstructor
public class ProductSearchController {

  private final HybridSearchService search;

  /**
   * One page of ranked results; see {@link SearchResponse} for the envelope.
   *
   * <p>Never fails because the embedding provider is down - it answers with keyword results and
   * {@code degraded=true}.
   */
  @GetMapping
  public SearchResponse search(@ModelAttribute SearchParams params) {
    return search.search(params.toQuery());
  }

  /** Brand / attribute / price facets over exactly the candidates {@link #search} ranks. */
  @GetMapping("/facets")
  public FacetsResponse facets(@ModelAttribute SearchParams params) {
    return search.facets(params.toQuery());
  }
}
