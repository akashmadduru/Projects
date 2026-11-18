package com.acme.ecommerce.product.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.ecommerce.product.support.AbstractProductIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * TD-P7-05 / TD-P7-19: product writes. Variants save in the same request (one transaction), an
 * omitted {@code images} keeps the images, {@code attributes: {}} and {@code brand: ""} clear, and
 * the list price is editable ({@code 0} clears it).
 */
@Transactional
class ProductWriteSemanticsIntegrationTest extends AbstractProductIntegrationTest {

  @Autowired private MockMvc mockMvc;

  private ResultActions send(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      String body)
      throws Exception {
    return mockMvc.perform(
        request
            .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private String create() throws Exception {
    return send(
            post("/api/products"),
            """
            {"sku":"WS-01","name":"Write semantics","priceAmount":80.00,"priceCurrency":"USD",
             "active":false,"brand":"Acme","attributes":{"Color":"Red"},"listPrice":100.00,
             "images":[{"url":"https://img.example/ws-1.png","displayOrder":0}],
             "variants":[{"sku":"WS-01-M","attributes":{"Size":"M"}},
                         {"sku":"WS-01-L","attributes":{"Size":"L"}}]}
            """)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.variants.length()").value(2))
        .andExpect(jsonPath("$.listPrice").value(100.0))
        .andExpect(jsonPath("$.discountPercent").value(20.0))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  // Outside the test transaction: inside it, the failed request's insert would stay visible until
  // the test ends, and this test is about the service's own transaction rolling it back.
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("a bad variant rolls the whole create back")
  void createIsAtomic() throws Exception {
    send(
            post("/api/products"),
            """
            {"sku":"WS-02","name":"Atomic","priceAmount":10,"priceCurrency":"USD","active":false,
             "variants":[{"sku":"WS-02-A"},{"sku":"WS-02-A"}]}
            """)
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            get("/api/products")
                .param("q", "WS-02")
                .header(HttpHeaders.AUTHORIZATION, bearerViewer()))
        .andExpect(jsonPath("$.page.totalElements").value(0));
  }

  @Test
  @DisplayName("update keeps omitted images and variants, clears on {} and \"\", reprices")
  void updateSemantics() throws Exception {
    String created = create();
    String id = JsonPath.read(created, "$.id");
    Integer version = JsonPath.read(created, "$.version");

    send(
            put("/api/products/" + id),
            """
            {"name":"Write semantics","priceAmount":90.00,"priceCurrency":"USD","active":false,
             "version":%d,"brand":"","attributes":{}}
            """
                .formatted(version))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.images.length()").value(1))
        .andExpect(jsonPath("$.variants.length()").value(2))
        .andExpect(jsonPath("$.brand").doesNotExist())
        .andExpect(jsonPath("$.salePrice").value(90.0))
        .andExpect(jsonPath("$.discountPercent").value(10.0));

    String detail =
        mockMvc
            .perform(get("/api/products/" + id).header(HttpHeaders.AUTHORIZATION, bearerViewer()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Integer next = JsonPath.read(detail, "$.version");
    send(
            put("/api/products/" + id),
            """
            {"name":"Write semantics","priceAmount":90.00,"priceCurrency":"USD","active":false,
             "version":%d,"listPrice":0,"variants":[]}
            """
                .formatted(next))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.listPrice").doesNotExist())
        .andExpect(jsonPath("$.variants.length()").value(0))
        .andExpect(jsonPath("$.images.length()").value(1));
  }

  @Test
  @DisplayName("a list price below the price is a 400")
  void listPriceBelowPrice() throws Exception {
    send(
            post("/api/products"),
            """
            {"sku":"WS-03","name":"Cheap list","priceAmount":50,"priceCurrency":"USD",
             "active":false,"listPrice":40}
            """)
        .andExpect(status().isBadRequest());
  }
}
