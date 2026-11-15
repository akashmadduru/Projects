package com.acme.ecommerce.product.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.ecommerce.common.core.error.UpstreamUnavailableException;
import com.acme.ecommerce.product.embedding.EmbedKind;
import com.acme.ecommerce.product.embedding.EmbeddingPort;
import com.acme.ecommerce.product.embedding.QueryEmbeddingCache;
import com.acme.ecommerce.product.repository.CategoryRepository;
import com.acme.ecommerce.product.repository.ProductRepository;
import com.acme.ecommerce.product.support.AbstractProductIntegrationTest;
import com.acme.ecommerce.product.support.SearchFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Failure policy of semantic search (decision D1) when the provider is configured but FAILING:
 * keyword results, {@code degraded=true}, never an error and never fake data.
 */
@Transactional
class HybridSearchProviderDownIntegrationTest extends AbstractProductIntegrationTest {

  @MockitoBean private EmbeddingPort port;
  @Autowired private MockMvc mockMvc;
  @Autowired private ProductRepository products;
  @Autowired private CategoryRepository categories;
  @Autowired private QueryEmbeddingCache cache;

  @BeforeEach
  void setUp() {
    when(port.modelId()).thenReturn("mock-model");
    when(port.dims()).thenReturn(1024);
    when(port.embed(any(), any()))
        .thenThrow(new UpstreamUnavailableException("bedrock-embeddings", null));
    cache.clear();
    SearchFixtures.seed(products, categories);
  }

  @Test
  @DisplayName("hybrid and semantic answer with keyword results and degraded=true, not an error")
  void degradesToKeyword() throws Exception {
    for (String mode : new String[] {"hybrid", "semantic"}) {
      mockMvc
          .perform(get("/api/products/search").param("q", "skillet").param("mode", mode))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.effectiveMode").value("keyword"))
          .andExpect(jsonPath("$.degraded").value(true))
          .andExpect(jsonPath("$.content[0].sku").value("SS-SKILLET"));
    }
    mockMvc
        .perform(get("/api/products/search").param("q", "skillet").param("mode", "keyword"))
        .andExpect(jsonPath("$.degraded").value(false));
  }

  @Test
  @DisplayName("repeated failures open the breaker, which stops calling the provider")
  void breakerOpens() throws Exception {
    for (int i = 0; i < 12; i++) {
      mockMvc
          .perform(get("/api/products/search").param("q", "skillet"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.degraded").value(true));
    }
    verify(port, atMost(6)).embed(any(), eq(EmbedKind.QUERY));
    assertThat(cache.size()).isZero();
  }
}
