package com.acme.ecommerce.product.search;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.ecommerce.product.repository.CategoryRepository;
import com.acme.ecommerce.product.repository.ProductRepository;
import com.acme.ecommerce.product.support.AbstractProductIntegrationTest;
import com.acme.ecommerce.product.support.SearchFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * No embedding provider configured ({@code product.embedding.provider=none}, the shipped default):
 * the feature is explicitly OFF. No fake provider is registered, search is keyword-only, and the
 * admin endpoints say so honestly.
 */
@Transactional
@TestPropertySource(properties = "product.embedding.provider=none")
class HybridSearchDisabledIntegrationTest extends AbstractProductIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ProductRepository products;
  @Autowired private CategoryRepository categories;

  @BeforeEach
  void setUp() {
    SearchFixtures.seed(products, categories);
  }

  @Test
  @DisplayName("search is keyword-only and says degraded; nothing is fabricated")
  void keywordOnly() throws Exception {
    mockMvc
        .perform(get("/api/products/search").param("q", "skillet"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.effectiveMode").value("keyword"))
        .andExpect(jsonPath("$.degraded").value(true))
        .andExpect(jsonPath("$.content[0].sku").value("SS-SKILLET"));
  }

  @Test
  @DisplayName("admin status reports the feature disabled; reindex is a 503 AI_UNAVAILABLE problem")
  void adminReportsDisabled() throws Exception {
    mockMvc
        .perform(
            get("/api/products/admin/embeddings/status")
                .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.provider").value("none"))
        .andExpect(jsonPath("$.embedded").value(0));
    mockMvc
        .perform(
            post("/api/products/admin/embeddings/reindex")
                .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));
  }
}
