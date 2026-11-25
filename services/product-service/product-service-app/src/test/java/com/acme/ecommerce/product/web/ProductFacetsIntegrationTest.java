package com.acme.ecommerce.product.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.ecommerce.product.domain.Product;
import com.acme.ecommerce.product.repository.ProductRepository;
import com.acme.ecommerce.product.support.AbstractProductIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * TD-P7-04: catalog facets are counted in SQL, each facet ignores its own filter, attribute values
 * match case-insensitively, and multi-values are repeated parameters (a comma is part of a value).
 *
 * <p>Fixtures use an {@code FC-} sku prefix and every request searches {@code q=FC-}. Facet answers
 * are cached per filter for 30 s, so every test uses a filter no other test uses.
 */
@Transactional
class ProductFacetsIntegrationTest extends AbstractProductIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private ProductRepository products;

  @BeforeEach
  void fixtures() {
    save("FC-01", "Acme", "{\"Color\":\"Red\",\"Size\":\"M\"}", "10.00");
    save("FC-02", "acme", "{\"Color\":\"red\",\"Size\":\"L\"}", "20.00");
    save("FC-03", "Globex", "{\"Color\":\"Blue\",\"Size\":\"M\"}", "30.00");
    save("FC-04", "Smith, Jones & Co", "{\"Color\":\"Blue\"}", "40.00");
    save("FC-05", null, "[1, 2]", "50.00");
  }

  private void save(String sku, String brand, String attributes, String price) {
    products.saveAndFlush(
        Product.builder()
            .sku(sku)
            .name("Facet fixture " + sku)
            .priceAmount(new BigDecimal(price))
            .priceCurrency("USD")
            .active(true)
            .brand(brand)
            .attributes(attributes)
            .createdAt(NOW)
            .updatedAt(NOW)
            .build());
  }

  private ResultActions call(String path, String... params) throws Exception {
    var request = get(path).param("q", "FC-").header(HttpHeaders.AUTHORIZATION, bearerViewer());
    for (int i = 0; i < params.length; i += 2) {
      request = request.param(params[i], params[i + 1]);
    }
    return mockMvc.perform(request).andExpect(status().isOk());
  }

  private ResultActions facets(String... params) throws Exception {
    return call("/api/products/facets", params);
  }

  private ResultActions list(String... params) throws Exception {
    return call("/api/products", params);
  }

  @Test
  @DisplayName("counts merge case variants and skip non-object attributes")
  void unfiltered() throws Exception {
    facets()
        .andExpect(jsonPath("$.brands.length()").value(3))
        .andExpect(jsonPath("$.brands[0].count").value(2))
        .andExpect(jsonPath("$.attributes[0].key").value("Color"))
        .andExpect(jsonPath("$.attributes[0].values.length()").value(2))
        .andExpect(jsonPath("$.attributes[0].values[*].count", containsInAnyOrder(2, 2)))
        .andExpect(jsonPath("$.attributes[1].key").value("Size"))
        .andExpect(jsonPath("$.price.min").value(10.0))
        .andExpect(jsonPath("$.price.max").value(50.0));
  }

  @Test
  @DisplayName("a selected brand still shows every other brand's count; other facets narrow")
  void brandIgnoresItsOwnFilter() throws Exception {
    facets("brand", "ACME")
        .andExpect(jsonPath("$.brands.length()").value(3))
        .andExpect(jsonPath("$.brands[?(@.value == 'Globex')].count").value(1))
        .andExpect(jsonPath("$.attributes[0].values.length()").value(1))
        .andExpect(jsonPath("$.attributes[0].values[0].count").value(2))
        .andExpect(jsonPath("$.price.max").value(20.0));
  }

  @Test
  @DisplayName("attribute filters are case-insensitive and the attribute ignores its own filter")
  void attributeIgnoresItsOwnFilter() throws Exception {
    list("attr.Color", "RED").andExpect(jsonPath("$.page.totalElements").value(2));
    facets("attr.Color", "RED")
        .andExpect(jsonPath("$.attributes[0].key").value("Color"))
        .andExpect(jsonPath("$.attributes[0].values.length()").value(2))
        .andExpect(jsonPath("$.attributes[1].key").value("Size"))
        .andExpect(jsonPath("$.attributes[1].values[*].value", containsInAnyOrder("M", "L")))
        .andExpect(jsonPath("$.brands.length()").value(1))
        .andExpect(jsonPath("$.brands[0].count").value(2));
  }

  @Test
  @DisplayName("the price range ignores the price bounds")
  void priceIgnoresItsOwnFilter() throws Exception {
    facets("maxPrice", "15")
        .andExpect(jsonPath("$.price.min").value(10.0))
        .andExpect(jsonPath("$.price.max").value(50.0))
        .andExpect(jsonPath("$.brands.length()").value(1))
        .andExpect(jsonPath("$.brands[0].count").value(1));
  }

  @Test
  @DisplayName("multi-values are repeated parameters; a comma belongs to the value")
  void repeatedParameters() throws Exception {
    list("brand", "Smith, Jones & Co").andExpect(jsonPath("$.page.totalElements").value(1));
    list("brand", "Acme", "brand", "Globex").andExpect(jsonPath("$.page.totalElements").value(3));
    list("brand", "Acme,Globex").andExpect(jsonPath("$.page.totalElements").value(0));
    list("attr.Size", "M", "attr.Size", "L").andExpect(jsonPath("$.page.totalElements").value(3));
  }
}
