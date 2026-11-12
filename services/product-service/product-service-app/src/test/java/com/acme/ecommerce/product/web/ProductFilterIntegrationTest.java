package com.acme.ecommerce.product.web;

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
 * The catalog's boolean and seller filters against a real Postgres.
 *
 * <p>Fixtures use a {@code FT-} sku prefix and every request searches {@code q=FT-}, so the five
 * Flyway-seeded products never interfere. The class is {@code @Transactional}, so the fixtures roll
 * back and the seeded-catalog assertions elsewhere stay exact.
 */
@Transactional
class ProductFilterIntegrationTest extends AbstractProductIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private ProductRepository products;

  @BeforeEach
  void fixtures() {
    // sku              seller         qty   discount featured verified
    save("FT-01", "Acme Tools", 5, "10.00", true, true);
    save("FT-02", "acme-garden", 0, null, false, false);
    save("FT-03", "Globex", null, "0.00", false, true);
    save("FT-04", null, 12, "25.50", true, false);
    save("FT-05", "100% Pure_Co", 1, null, false, false);
  }

  private void save(
      String sku,
      String seller,
      Integer quantity,
      String discount,
      boolean featured,
      boolean verified) {
    Product product =
        Product.builder()
            .sku(sku)
            .name("Fixture " + sku)
            .priceAmount(new BigDecimal("10.00"))
            .priceCurrency("USD")
            .active(true)
            .sellerName(seller)
            .discountPercent(discount == null ? null : new BigDecimal(discount))
            .isFeatured(featured)
            .isSellerVerified(verified)
            .createdAt(NOW)
            .updatedAt(NOW)
            .build();
    product.applyInventorySignal(quantity, NOW);
    products.saveAndFlush(product);
  }

  private ResultActions list(String... params) throws Exception {
    var request =
        get("/api/products").param("q", "FT-").header(HttpHeaders.AUTHORIZATION, bearerViewer());
    for (int i = 0; i < params.length; i += 2) {
      request = request.param(params[i], params[i + 1]);
    }
    return mockMvc.perform(request);
  }

  @Test
  @DisplayName("without a filter every fixture is returned")
  void baseline() throws Exception {
    list().andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(5));
  }

  @Test
  @DisplayName("seller matches a case-insensitive substring and ignores products with no seller")
  void sellerSubstring() throws Exception {
    list("seller", "ACME")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].sku").value("FT-01"))
        .andExpect(jsonPath("$.content[1].sku").value("FT-02"));
  }

  @Test
  @DisplayName("seller treats % and _ literally rather than as LIKE wildcards")
  void sellerEscapesWildcards() throws Exception {
    list("seller", "100%")
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].sku").value("FT-05"));
    list("seller", "Pure_Co").andExpect(jsonPath("$.page.totalElements").value(1));
    list("seller", "Pure-Co").andExpect(jsonPath("$.page.totalElements").value(0));
    list("seller", "%").andExpect(jsonPath("$.page.totalElements").value(1));
  }

  @Test
  @DisplayName("inStock hides products known to be out of stock but keeps unknown quantity")
  void inStock() throws Exception {
    list("inStock", "true")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(4))
        .andExpect(jsonPath("$.content[?(@.sku == 'FT-02')]").isEmpty())
        .andExpect(jsonPath("$.content[?(@.sku == 'FT-03')]").isNotEmpty());
  }

  @Test
  @DisplayName("onSale keeps only products with a positive discount")
  void onSale() throws Exception {
    list("onSale", "true")
        .andExpect(jsonPath("$.page.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].sku").value("FT-01"))
        .andExpect(jsonPath("$.content[1].sku").value("FT-04"));
  }

  @Test
  @DisplayName("featured keeps only top picks")
  void featured() throws Exception {
    list("featured", "true")
        .andExpect(jsonPath("$.page.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].sku").value("FT-01"))
        .andExpect(jsonPath("$.content[1].sku").value("FT-04"))
        .andExpect(jsonPath("$.content[0].featured").value(true));
  }

  @Test
  @DisplayName("verifiedSeller keeps only products from verified sellers")
  void verifiedSeller() throws Exception {
    list("verifiedSeller", "true")
        .andExpect(jsonPath("$.page.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].sku").value("FT-01"))
        .andExpect(jsonPath("$.content[1].sku").value("FT-03"));
  }

  @Test
  @DisplayName("false switches never narrow - they mean 'no restriction'")
  void falseSwitchesAreInert() throws Exception {
    list("inStock", "false", "onSale", "false", "featured", "false", "verifiedSeller", "false")
        .andExpect(jsonPath("$.page.totalElements").value(5));
  }

  @Test
  @DisplayName("filters combine with AND, and with the existing numeric filters")
  void combine() throws Exception {
    list("featured", "true", "inStock", "true", "onSale", "true", "minDiscount", "20")
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].sku").value("FT-04"));
    list("verifiedSeller", "true", "seller", "globex")
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].sku").value("FT-03"));
  }

  @Test
  @DisplayName("oversized text filters and malformed booleans are 400 problem documents")
  void validation() throws Exception {
    list("seller", "x".repeat(201))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").isNotEmpty());
    list("q", "x".repeat(201)).andExpect(status().isBadRequest());
    list("inStock", "maybe").andExpect(status().isBadRequest());
  }
}
