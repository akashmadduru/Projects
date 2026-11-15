package com.acme.ecommerce.product.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.ecommerce.product.domain.Product;
import com.acme.ecommerce.product.embedding.EmbeddingAdminService;
import com.acme.ecommerce.product.embedding.EmbeddingPort;
import com.acme.ecommerce.product.embedding.EmbeddingReconciler;
import com.acme.ecommerce.product.repository.CategoryRepository;
import com.acme.ecommerce.product.repository.ProductRepository;
import com.acme.ecommerce.product.support.AbstractProductIntegrationTest;
import com.acme.ecommerce.product.support.SearchFixtures;
import com.acme.ecommerce.product.support.SearchFixtures.Seeded;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Semantic and hybrid search against a REAL Postgres with pgvector, using the deterministic fake
 * embedding provider (profile {@code test}). The fake is lexical, not semantic, so these tests
 * prove the plumbing - migration, reconciler, RRF fusion, filters, facets, paging, security - not
 * model quality. Rolled back per test; fixtures use an {@code SS-} sku prefix.
 */
@Transactional
class HybridSearchIntegrationTest extends AbstractProductIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ProductRepository products;
  @Autowired private CategoryRepository categories;
  @Autowired private EmbeddingPort port;
  @Autowired private EmbeddingReconciler reconciler;
  @Autowired private EmbeddingAdminService admin;
  @Autowired private JdbcTemplate jdbc;

  private Seeded seeded;

  @BeforeEach
  void fixtures() {
    seeded = SearchFixtures.seed(products, categories);
  }

  private ResultActions search(String... params) throws Exception {
    MockHttpServletRequestBuilder request = get("/api/products/search");
    for (int i = 0; i < params.length; i += 2) {
      request = request.param(params[i], params[i + 1]);
    }
    return mockMvc.perform(request);
  }

  private void embedEverything() {
    reconciler.reconcile(port);
  }

  @Nested
  @DisplayName("migration and reconciler")
  class Pipeline {

    @Test
    @DisplayName("V10 installs pgvector, and the HNSW index uses the cosine operator class")
    void migration() {
      assertThat(
              jdbc.queryForObject(
                  "select extversion from pg_extension where extname = 'vector'", String.class))
          .isNotBlank();
      assertThat(
              jdbc.queryForObject(
                  "select indexdef from pg_indexes where indexname = 'idx_product_embeddings_hnsw'",
                  String.class))
          .contains("hnsw")
          .contains("vector_cosine_ops");
    }

    @Test
    @DisplayName("embeds every active product exactly once and skips unchanged ones on re-run")
    void reconcileIsIdempotent() {
      EmbeddingReconciler.Result first = reconciler.reconcile(port);
      EmbeddingAdminService.Status after = admin.status();
      assertThat(first.embedded()).isEqualTo(after.active());
      assertThat(after.embedded()).isEqualTo(after.active());
      assertThat(after.stale()).isZero();
      assertThat(after.failed()).isZero();

      EmbeddingReconciler.Result second = reconciler.reconcile(port);
      assertThat(second.embedded()).isZero();
      assertThat(second.batches()).isZero();
    }

    @Test
    @DisplayName("inactive products are never embedded")
    void inactiveSkipped() {
      embedEverything();
      Long rows =
          jdbc.queryForObject(
              "select count(*) from product.product_embeddings where product_id = ?",
              Long.class,
              seeded.retired());
      assertThat(rows).isZero();
    }

    @Test
    @DisplayName(
        "an edit that changes the text is re-embedded; one that does not is only re-stamped")
    void contentHashSkip() {
      embedEverything();
      Instant future = Instant.parse("2099-01-01T00:00:00Z");

      // Price is not part of the embedding text: updated_at moves, the hash does not.
      Product earbuds = products.findById(seeded.earbuds()).orElseThrow();
      earbuds.setPriceAmount(new BigDecimal("59.00"));
      earbuds.setUpdatedAt(future);
      products.saveAndFlush(earbuds);
      EmbeddingReconciler.Result priceOnly = reconciler.reconcile(port);
      assertThat(priceOnly.embedded()).isZero();
      assertThat(priceOnly.unchanged()).isEqualTo(1);

      // The description is: re-embedded.
      Product skillet = products.findById(seeded.skillet()).orElseThrow();
      skillet.setDescription("Enamelled dutch oven, a completely different product now.");
      skillet.setUpdatedAt(future);
      products.saveAndFlush(skillet);
      EmbeddingReconciler.Result textChange = reconciler.reconcile(port);
      assertThat(textChange.embedded()).isEqualTo(1);
    }

    @Test
    @DisplayName("an edit made after the batch's snapshot is picked up on the next run")
    void editAfterSnapshotIsNotLost() {
      embedEverything();
      Product shoes = products.findById(seeded.runningShoes()).orElseThrow();
      shoes.setDescription("Rewritten after the embedding run.");
      shoes.setUpdatedAt(Instant.parse("2099-01-01T00:00:00Z"));
      products.saveAndFlush(shoes);
      assertThat(admin.status().stale()).isEqualTo(1);
      reconciler.reconcile(port);
      assertThat(admin.status().stale()).isZero();
    }
  }

  @Nested
  @DisplayName("GET /api/products/search")
  class Search {

    @BeforeEach
    void embed() {
      embedEverything();
    }

    @Test
    @DisplayName("hybrid ranks the lexically closest product first and says why it matched")
    void hybridRanking() throws Exception {
      search("q", "bluetooth headphones")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.effectiveMode").value("hybrid"))
          .andExpect(jsonPath("$.degraded").value(false))
          .andExpect(jsonPath("$.content[0].sku").value("SS-HEADPHONES"))
          .andExpect(jsonPath("$.matchedBy." + seeded.headphones()).value("both"))
          .andExpect(jsonPath("$.page.number").value(0))
          .andExpect(jsonPath("$.page.size").value(20));
    }

    @Test
    @DisplayName("inactive products never appear, in any mode")
    void inactiveHidden() throws Exception {
      for (String mode : List.of("keyword", "semantic", "hybrid")) {
        search("q", "bluetooth headphones", "mode", mode)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[?(@.sku == 'SS-RETIRED')]").isEmpty());
      }
    }

    @Test
    @DisplayName("keyword mode is not degraded and reports keyword matches only")
    void keywordMode() throws Exception {
      search("q", "skillet", "mode", "keyword")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.effectiveMode").value("keyword"))
          .andExpect(jsonPath("$.degraded").value(false))
          .andExpect(jsonPath("$.content[0].sku").value("SS-SKILLET"))
          .andExpect(jsonPath("$.matchedBy." + seeded.skillet()).value("keyword"));
    }

    @Test
    @DisplayName("semantic mode retrieves by vector alone")
    void semanticMode() throws Exception {
      search("q", "bluetooth earbuds charging", "mode", "semantic")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.effectiveMode").value("semantic"))
          .andExpect(jsonPath("$.degraded").value(false))
          .andExpect(jsonPath("$.content[0].sku").value("SS-EARBUDS"))
          .andExpect(jsonPath("$.matchedBy." + seeded.earbuds()).value("semantic"));
    }

    @Test
    @DisplayName("filters are applied before ranking: price, rating, brand and category")
    void explicitFilters() throws Exception {
      search("q", "bluetooth").andExpect(jsonPath("$.page.totalElements").value(2));
      search("q", "bluetooth", "maxPrice", "100")
          .andExpect(jsonPath("$.page.totalElements").value(1))
          .andExpect(jsonPath("$.content[0].sku").value("SS-EARBUDS"));
      search("q", "bluetooth", "minPrice", "100")
          .andExpect(jsonPath("$.page.totalElements").value(1))
          .andExpect(jsonPath("$.content[0].sku").value("SS-HEADPHONES"));
      search("q", "bluetooth", "minRating", "4.5")
          .andExpect(jsonPath("$.page.totalElements").value(1))
          .andExpect(jsonPath("$.content[0].sku").value("SS-HEADPHONES"));
      search("q", "bluetooth", "brand", "GLOBEX")
          .andExpect(jsonPath("$.page.totalElements").value(2));
      search("q", "bluetooth", "brand", "Acme")
          .andExpect(jsonPath("$.page.totalElements").value(0));
      // The shoes sit in "Running", a child of "Footwear": filtering by the PARENT includes them.
      search("q", "running shoes", "categoryId", seeded.footwear().toString())
          .andExpect(jsonPath("$.page.totalElements").value(1))
          .andExpect(jsonPath("$.content[0].sku").value("SS-SHOES"));
      search("q", "bluetooth", "categoryId", seeded.footwear().toString())
          .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("natural-language price, rating and brand are extracted, reported and applied")
    void naturalLanguageFilters() throws Exception {
      search("q", "bluetooth earbuds charging case under 100")
          .andExpect(jsonPath("$.interpretedFilters.maxPrice").value(100))
          .andExpect(jsonPath("$.content[0].sku").value("SS-EARBUDS"));
      search("q", "bluetooth earbuds charging case under 50")
          .andExpect(jsonPath("$.interpretedFilters.maxPrice").value(50))
          .andExpect(jsonPath("$.content.length()").value(0));
      search("q", "bluetooth headphones 5 stars and above")
          .andExpect(jsonPath("$.interpretedFilters.minRating").value(5))
          .andExpect(jsonPath("$.content.length()").value(0));
      search("q", "bluetooth headphones from Acme")
          .andExpect(jsonPath("$.interpretedFilters.brand").value("Acme"))
          .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("an explicit parameter beats the extracted one")
    void explicitBeatsExtracted() throws Exception {
      search("q", "bluetooth headphones under 100", "maxPrice", "300")
          .andExpect(jsonPath("$.interpretedFilters.maxPrice").doesNotExist())
          .andExpect(jsonPath("$.content[0].sku").value("SS-HEADPHONES"));
    }

    @Test
    @DisplayName(
        "sorting and paging operate inside the fused set, and totals do not move with the page")
    void sortAndPage() throws Exception {
      search("q", "bluetooth", "sort", "price_asc")
          .andExpect(jsonPath("$.content[0].sku").value("SS-EARBUDS"))
          .andExpect(jsonPath("$.content[1].sku").value("SS-HEADPHONES"));
      search("q", "bluetooth", "sort", "price_desc")
          .andExpect(jsonPath("$.content[0].sku").value("SS-HEADPHONES"));
      search("q", "bluetooth", "sort", "price_asc", "size", "1", "page", "1")
          .andExpect(jsonPath("$.content.length()").value(1))
          .andExpect(jsonPath("$.content[0].sku").value("SS-HEADPHONES"))
          .andExpect(jsonPath("$.page.totalElements").value(2))
          .andExpect(jsonPath("$.page.totalPages").value(2));
      search("q", "bluetooth", "size", "1", "page", "5")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content.length()").value(0))
          .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    @DisplayName("facets describe exactly the candidates the list is drawn from")
    void facetsAgreeWithList() throws Exception {
      search("q", "bluetooth", "maxPrice", "100", "mode", "keyword")
          .andExpect(jsonPath("$.page.totalElements").value(1));
      mockMvc
          .perform(
              get("/api/products/search/facets")
                  .param("q", "bluetooth")
                  .param("maxPrice", "100")
                  .param("mode", "keyword"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.brands.length()").value(1))
          .andExpect(jsonPath("$.brands[0].value").value("Globex"))
          .andExpect(jsonPath("$.brands[0].count").value(1))
          .andExpect(jsonPath("$.price.max").value(60.0));
    }
  }

  @Nested
  @DisplayName("validation")
  class Validation {

    @Test
    @DisplayName("rejects a missing, too short or too long q, a bad mode, sort and size")
    void rejects() throws Exception {
      mockMvc.perform(get("/api/products/search")).andExpect(status().isBadRequest());
      search("q", "a")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
      search("q", "x".repeat(201)).andExpect(status().isBadRequest());
      search("q", "shoes", "mode", "magic").andExpect(status().isBadRequest());
      search("q", "shoes", "sort", "random").andExpect(status().isBadRequest());
      search("q", "shoes", "size", "51").andExpect(status().isBadRequest());
      search("q", "shoes", "minPrice", "10", "maxPrice", "5").andExpect(status().isBadRequest());
      search("q", "shoes", "minRating", "6").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("accepts exactly 2 and exactly 200 characters")
    void boundaries() throws Exception {
      search("q", "ab", "mode", "keyword").andExpect(status().isOk());
      search("q", "x".repeat(200), "mode", "keyword").andExpect(status().isOk());
    }

    @Test
    @DisplayName("the existing GET /api/products?q= is untouched")
    void legacyListUntouched() throws Exception {
      mockMvc
          .perform(get("/api/products").param("q", "SS-"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.effectiveMode").doesNotExist())
          .andExpect(jsonPath("$.page.totalElements").value(5));
    }
  }

  @Nested
  @DisplayName("admin endpoints")
  class Admin {

    @Test
    @DisplayName("status: 401 anonymous, 403 for a customer, 200 for a catalog manager")
    void statusSecurity() throws Exception {
      mockMvc
          .perform(get("/api/products/admin/embeddings/status"))
          .andExpect(status().isUnauthorized());
      mockMvc
          .perform(
              get("/api/products/admin/embeddings/status")
                  .header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isForbidden());
      embedEverything();
      mockMvc
          .perform(
              get("/api/products/admin/embeddings/status")
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.enabled").value(true))
          .andExpect(jsonPath("$.model").value(port.modelId()))
          .andExpect(jsonPath("$.dims").value(1024))
          .andExpect(jsonPath("$.stale").value(0));
    }

    @Test
    @DisplayName("reindex: guarded like status; queues work that the next run performs")
    void reindex() throws Exception {
      mockMvc
          .perform(post("/api/products/admin/embeddings/reindex"))
          .andExpect(status().isUnauthorized());
      mockMvc
          .perform(
              post("/api/products/admin/embeddings/reindex")
                  .header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isForbidden());

      embedEverything();
      long active = admin.status().active();
      mockMvc
          .perform(
              post("/api/products/admin/embeddings/reindex")
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager()))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.queued").value(active));
      assertThat(admin.status().stale()).isEqualTo(active);
      assertThat(reconciler.reconcile(port).embedded()).isEqualTo((int) active);
      assertThat(admin.status().stale()).isZero();
    }

    @Test
    @DisplayName("reindex of one product, and a 404 for an unknown product")
    void reindexOne() throws Exception {
      embedEverything();
      mockMvc
          .perform(
              post("/api/products/admin/embeddings/reindex")
                  .param("productId", seeded.skillet().toString())
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager()))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.queued").value(1));
      assertThat(reconciler.reconcile(port).embedded()).isEqualTo(1);
      mockMvc
          .perform(
              post("/api/products/admin/embeddings/reindex")
                  .param("productId", "00000000-0000-4000-8000-000000000000")
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager()))
          .andExpect(status().isNotFound());
    }
  }
}
