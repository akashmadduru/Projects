package com.acme.ecommerce.product.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import com.acme.ecommerce.product.repository.ProductRepository;
import com.acme.ecommerce.product.service.ProductCatalogService;
import com.acme.ecommerce.product.support.AbstractProductIntegrationTest;
import com.acme.ecommerce.product.web.dto.CreateProductRequest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** TD-P9-03: seller-listed products are reviewed before they go live. */
@Transactional
class ProductModerationIntegrationTest extends AbstractProductIntegrationTest {

  @Autowired private ProductCatalogService catalog;
  @Autowired private ProductModerationService moderation;
  @Autowired private ProductRepository products;
  @Autowired private MockMvc mockMvc;

  private ProductResponse sellerProduct(String name) {
    return catalog.createFor(
        CreateProductRequest.builder()
            .sku("MOD-" + UUID.randomUUID().toString().substring(0, 8))
            .name(name)
            .priceAmount(new BigDecimal("499.00"))
            .priceCurrency("INR")
            .active(true)
            .build(),
        UUID.randomUUID(),
        "SELLER");
  }

  @Test
  @DisplayName("a seller's new product is hidden and waiting for review, with warnings")
  void sellerProductStartsPending() {
    ProductResponse p = sellerProduct("Replica watch");

    assertThat(p.isActive()).isFalse();
    assertThat(p.getModerationStatus()).isEqualTo("PENDING_REVIEW");
    ProductModerationService.QueueItem item =
        moderation.queue("PENDING_REVIEW", 0, 50).content().stream()
            .filter(i -> i.id().equals(p.getId()))
            .findFirst()
            .orElseThrow();
    assertThat(item.flags()).contains("NO_IMAGE", "NO_DESCRIPTION", "BANNED_TERM");
  }

  @Test
  @DisplayName("approving activates the product; rejecting needs a note and keeps it hidden")
  void approveAndReject() {
    UUID reviewer = UUID.randomUUID();
    ProductResponse good = sellerProduct("Cotton t-shirt");
    ProductResponse bad = sellerProduct("Mystery box");

    moderation.decide(good.getId(), "APPROVE", null, reviewer);
    assertThatThrownBy(() -> moderation.decide(bad.getId(), "REJECT", " ", reviewer))
        .isInstanceOf(CodedException.class);
    moderation.decide(bad.getId(), "REJECT", "Add real photos and a description", reviewer);

    var approved = products.findById(good.getId()).orElseThrow();
    assertThat(approved.isActive()).isTrue();
    assertThat(approved.getModerationStatus()).isEqualTo("APPROVED");
    assertThat(approved.getReviewedBy()).isEqualTo(reviewer);
    var rejected = products.findById(bad.getId()).orElseThrow();
    assertThat(rejected.isActive()).isFalse();
    assertThat(rejected.getModerationStatus()).isEqualTo("REJECTED");
    assertThat(rejected.getModerationNote()).isEqualTo("Add real photos and a description");
  }

  @Test
  @DisplayName("platform products are live straight away")
  void platformProductsSkipReview() {
    ProductResponse p =
        catalog.createFor(
            CreateProductRequest.builder()
                .sku("MOD-P-" + UUID.randomUUID().toString().substring(0, 8))
                .name("Platform item")
                .priceAmount(new BigDecimal("10.00"))
                .priceCurrency("USD")
                .active(true)
                .build(),
            UUID.fromString("00000000-0000-0000-0000-00000000a11e"),
            "PLATFORM");

    assertThat(p.isActive()).isTrue();
    assertThat(p.getModerationStatus()).isEqualTo("APPROVED");
  }

  @Test
  @DisplayName("the queue is 401 anonymous, 403 for a customer, 200 for a catalog manager")
  void queueSecurity() throws Exception {
    mockMvc.perform(get("/api/products/admin/moderation")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            get("/api/products/admin/moderation").header(HttpHeaders.AUTHORIZATION, bearerViewer()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/products/admin/moderation")
                .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager()))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("a price far from the category average is flagged")
  void priceOutlierFlag() {
    assertThat(moderation.flags("x", "y", true, new BigDecimal("1000"), new BigDecimal("100")))
        .containsExactly("PRICE_OUTLIER");
    assertThat(moderation.flags("x", "y", true, new BigDecimal("150"), new BigDecimal("100")))
        .isEmpty();
    assertThat(moderation.flags("x", "y", true, new BigDecimal("150"), null)).isEmpty();
  }
}
