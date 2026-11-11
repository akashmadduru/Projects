package com.acme.ecommerce.product.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.ecommerce.product.support.AbstractProductIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end behaviour of the two Phase 1E endpoints, over the real filter chain and a real
 * database.
 *
 * <p>The security assertions are the point of this class. product-service validates tokens itself
 * rather than trusting the gateway's {@code X-User-*} headers, and "it validates them" is a claim
 * that is worth exactly as much as the test proving a wrong-audience token is refused.
 */
class ProductEndpointsIntegrationTest extends AbstractProductIntegrationTest {

  /** Seeded by V1__product_baseline.sql with a fixed id, so no discovery call is needed first. */
  private static final String KNOWN_ID = "11111111-1111-4111-8111-111111111111";

  @Autowired private MockMvc mockMvc;

  @Nested
  @DisplayName("token validation")
  class TokenValidation {

    @Test
    @DisplayName("POST without a token is rejected with a 401 problem+json document")
    void postWithoutTokenIsRejected() throws Exception {
      // Product mutations require a token; only GET endpoints are public
      mockMvc
          .perform(
              post("/api/products")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"sku\":\"SKU-NEW-01\",\"name\":\"New Widget\",\"priceAmount\":9.99,"
                          + "\"priceCurrency\":\"USD\",\"active\":true,\"initialQuantity\":10}"))
          .andExpect(status().isUnauthorized())
          // Not a bodiless 401: every error this platform emits is problem+json,
          // including the most common one in the system (an expired token).
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
          .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("a validly-signed token with the platform's issuer and audience is accepted")
    void validTokenIsAccepted() throws Exception {
      mockMvc
          .perform(get("/api/products").header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a token minted for a different relying party is rejected")
    void wrongAudienceIsRejected() throws Exception {
      // Correctly signed by the platform's own key: only the `aud` claim differs.
      // Without an audience validator this token would be accepted, and any token
      // this issuer ever minted would be replayable against the catalog.
      mockMvc
          .perform(
              get("/api/products")
                  .header(
                      HttpHeaders.AUTHORIZATION, "Bearer " + KEYS.wrongAudienceToken("someone")))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("a token from an unexpected issuer is rejected")
    void wrongIssuerIsRejected() throws Exception {
      mockMvc
          .perform(
              get("/api/products")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + KEYS.wrongIssuerToken("someone")))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredTokenIsRejected() throws Exception {
      mockMvc
          .perform(
              get("/api/products")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + KEYS.expiredToken("someone")))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a gateway-style X-User-Roles header grants nothing on its own")
    void trustedHeadersAreNotCredentials() throws Exception {
      // The gateway injects these from a validated token, and this service logs
      // them - but it must never authorize on them. If it did, anything that can
      // reach this port could grant itself ADMIN with a header.
      mockMvc
          .perform(
              get("/api/products")
                  .header("X-User-Id", "8f14e45f-ceea-4e78-9c39-4f5d2b1a0001")
                  .header("X-User-Roles", "ADMIN")
                  .header("X-User-Email", "attacker@example.com"))
          .andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @DisplayName("unauthenticated GET endpoints (Phase 1F public reads)")
  class UnauthenticatedGetProductsTests {

    @Test
    @DisplayName("GET /api/products returns 200 with paginated results without a token")
    void listProductsWithoutToken() throws Exception {
      mockMvc
          .perform(get("/api/products"))
          .andExpect(status().isOk())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(jsonPath("$.content.length()").value(5))
          .andExpect(jsonPath("$.page.totalElements").value(5))
          .andExpect(jsonPath("$.content[0].sku").value("SKU-DOCK-USBC"))
          .andExpect(jsonPath("$.content[0].priceAmount").value(199.95))
          .andExpect(jsonPath("$.content[0].priceCurrency").value("USD"));
    }

    @Test
    @DisplayName("GET /api/products/{id} returns 200 with product detail without a token")
    void findProductByIdWithoutToken() throws Exception {
      mockMvc
          .perform(get("/api/products/{id}", KNOWN_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(KNOWN_ID))
          .andExpect(jsonPath("$.sku").value("SKU-KEYBOARD-01"))
          .andExpect(jsonPath("$.name").value("Mechanical Keyboard"))
          .andExpect(jsonPath("$.priceAmount").value(129.99))
          .andExpect(jsonPath("$.priceCurrency").value("USD"))
          .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName(
        "GET /api/products/catalog/products/{id} returns 200 with aggregate without a token")
    void getAggregateWithoutToken() throws Exception {
      mockMvc
          .perform(get("/api/products/catalog/products/{id}", KNOWN_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.product.id").value(KNOWN_ID))
          .andExpect(jsonPath("$.product.sku").value("SKU-KEYBOARD-01"))
          .andExpect(jsonPath("$.product.name").value("Mechanical Keyboard"))
          .andExpect(jsonPath("$.inventory").doesNotExist());
    }
  }

  @Nested
  @DisplayName("GET /api/products")
  class ListEndpoint {

    @Test
    @DisplayName("returns the Flyway-seeded catalog, sorted by sku, in the PagedModel envelope")
    void returnsSeededCatalog() throws Exception {
      mockMvc
          .perform(get("/api/products").header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isOk())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(jsonPath("$.content.length()").value(5))
          .andExpect(jsonPath("$.page.totalElements").value(5))
          .andExpect(jsonPath("$.page.size").value(20))
          .andExpect(jsonPath("$.page.number").value(0))
          // Default sort is sku ASC, and sku is UNIQUE - so the ordering is total
          // and a client walking pages cannot see a row twice or miss one.
          .andExpect(jsonPath("$.content[0].sku").value("SKU-DOCK-USBC"))
          .andExpect(jsonPath("$.content[4].sku").value("SKU-MOUSE-01"))
          // Price is an exact decimal with its currency, never a bare float.
          .andExpect(jsonPath("$.content[0].priceAmount").value(199.95))
          .andExpect(jsonPath("$.content[0].priceCurrency").value("USD"));
    }

    @Test
    @DisplayName("inactive products are returned too - Phase 1E deliberately does not filter")
    void doesNotFilterOnActive() throws Exception {
      mockMvc
          .perform(get("/api/products").header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[?(@.active == false)].sku").value("SKU-HEADSET-01"));
    }

    @Test
    @DisplayName("honours page and size")
    void paginates() throws Exception {
      mockMvc
          .perform(
              get("/api/products")
                  .param("page", "1")
                  .param("size", "2")
                  .header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content.length()").value(2))
          .andExpect(jsonPath("$.page.number").value(1))
          .andExpect(jsonPath("$.page.size").value(2))
          .andExpect(jsonPath("$.page.totalPages").value(3))
          // Page 1 of a sku-sorted catalog: rows 3 and 4 of the five.
          .andExpect(jsonPath("$.content[0].sku").value("SKU-KEYBOARD-01"));
    }

    @Test
    @DisplayName("a page size beyond the cap is clamped, not honoured")
    void clampsOversizedPageRequest() throws Exception {
      // spring.data.web.pageable.max-page-size = 100. Without the cap a caller
      // could turn a paged read into a full-table export and trip the gateway's
      // 1s productCb slow-call threshold for everyone else.
      mockMvc
          .perform(
              get("/api/products")
                  .param("size", "5000")
                  .header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.page.size").value(100));
    }
  }

  @Nested
  @DisplayName("GET /api/products/{id}")
  class ByIdEndpoint {

    @Test
    @DisplayName("returns the seeded product")
    void returnsKnownProduct() throws Exception {
      mockMvc
          .perform(
              get("/api/products/{id}", KNOWN_ID).header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(KNOWN_ID))
          .andExpect(jsonPath("$.sku").value("SKU-KEYBOARD-01"))
          .andExpect(jsonPath("$.name").value("Mechanical Keyboard"))
          .andExpect(jsonPath("$.priceAmount").value(129.99))
          .andExpect(jsonPath("$.priceCurrency").value("USD"))
          .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("an unknown id is a 404 problem+json document, not an empty 200")
    void unknownIdIsProblemJson() throws Exception {
      mockMvc
          .perform(
              get("/api/products/{id}", "00000000-0000-4000-8000-0000000000ff")
                  .header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isNotFound())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
          .andExpect(jsonPath("$.title").value("Resource not found"))
          .andExpect(
              jsonPath("$.type").value("https://errors.acme-ecommerce.internal/resource-not-found"))
          .andExpect(jsonPath("$.resourceType").value("Product"))
          .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("a non-UUID id is a 400, not a 500")
    void malformedIdIsBadRequest() throws Exception {
      mockMvc
          .perform(
              get("/api/products/{id}", "not-a-uuid")
                  .header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName(
        "GET without a token succeeds for known id, returns 404 for unknown id (no probing)")
    void unauthenticatedGetIsAllowed() throws Exception {
      // Phase 1F: GET /api/products/{id} is now public. A known id returns 200 with the product;
      // an unknown id returns 404. No 401, because authentication is not required for reads.
      mockMvc
          .perform(get("/api/products/{id}", KNOWN_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(KNOWN_ID))
          .andExpect(jsonPath("$.sku").value("SKU-KEYBOARD-01"));

      // An unknown id returns 404, which does theoretically allow probing - but that is a separate
      // decision (rate-limit anonymous reads, or return 200 with a cache-busting marker) and
      // outside
      // this phase's scope. Catalog queries are public, and their response codes follow the same
      // pattern as any other public API.
      mockMvc
          .perform(get("/api/products/{id}", "00000000-0000-4000-8000-0000000000ff"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
  }

  @Nested
  @DisplayName("GET /api/products/catalog/products/{id}")
  // NO EUREKA REGISTRY IN THIS TEST JVM (docs/HLD-catalog-enrichment-and-saga.md S7.3,
  // docs/adr/0003 Decision 2). InventoryFeignClient's lb://INVENTORY-SERVICE call therefore
  // cannot resolve a live instance no matter what productId is requested - which is exactly
  // the "inventory-service unreachable" degradation path CatalogAggregateService exists to
  // handle. These tests prove the aggregate endpoint answers 200 with inventory: null rather
  // than propagating that failure as a 5xx; a mocked-Feign-client unit test
  // (CatalogAggregateServiceTest) covers the other degradation causes (a genuine 404, and the
  // circuit breaker itself opening) that this Eureka-less environment cannot exercise directly.
  class AggregateEndpoint {

    @Test
    @DisplayName(
        "returns the product with inventory: null when inventory-service cannot be reached")
    void degradesToNullInventoryWhenInventoryServiceUnreachable() throws Exception {
      mockMvc
          .perform(
              get("/api/products/catalog/products/{id}", KNOWN_ID)
                  .header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.product.id").value(KNOWN_ID))
          .andExpect(jsonPath("$.product.sku").value("SKU-KEYBOARD-01"))
          .andExpect(jsonPath("$.inventory").doesNotExist());
    }

    @Test
    @DisplayName("an unknown product id is a 404 problem+json document, not a partial 200")
    void unknownProductIdIsProblemJson() throws Exception {
      mockMvc
          .perform(
              get("/api/products/catalog/products/{id}", "00000000-0000-4000-8000-0000000000ff")
                  .header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isNotFound())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
          .andExpect(jsonPath("$.resourceType").value("Product"));
    }

    @Test
    @DisplayName("GET without a token is allowed and returns the aggregate (Phase 1F public reads)")
    void unauthenticatedGetIsAllowed() throws Exception {
      // Phase 1F: GET /api/products/catalog/products/{id} is now public, like all catalog reads
      mockMvc
          .perform(get("/api/products/catalog/products/{id}", KNOWN_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.product.id").value(KNOWN_ID))
          .andExpect(jsonPath("$.product.sku").value("SKU-KEYBOARD-01"))
          .andExpect(jsonPath("$.inventory").doesNotExist());
    }
  }

  @Nested
  @DisplayName("POST /api/products")
  // ROLLED BACK AFTER EACH TEST. This class shares the singleton Postgres container/Spring context
  // with ListEndpoint's count-sensitive assertions (Flyway seeds exactly five rows) - without this,
  // a create test that runs before ListEndpoint would leave extra rows behind, and ListEndpoint's
  // fixed-count assertions (which are not test pollution to "fix", they are the existing,
  // unmodified Phase 1E behaviour this pass must not disturb) would fail depending on JUnit's
  // (unspecified) nested-class execution order.
  @Transactional
  class CreateEndpoint {

    @Test
    @DisplayName("CATALOG_MANAGER can create a product")
    void catalogManagerCanCreate() throws Exception {
      mockMvc
          .perform(
              post("/api/products")
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"sku\":\"SKU-NEW-01\",\"name\":\"New Widget\",\"priceAmount\":9.99,"
                          + "\"priceCurrency\":\"USD\",\"active\":true,\"initialQuantity\":10}"))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.sku").value("SKU-NEW-01"))
          .andExpect(jsonPath("$.name").value("New Widget"));
    }

    @Test
    @DisplayName("a VIEWER is forbidden from creating a product")
    void viewerCannotCreate() throws Exception {
      mockMvc
          .perform(
              post("/api/products")
                  .header(HttpHeaders.AUTHORIZATION, bearerViewer())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"sku\":\"SKU-NEW-02\",\"name\":\"New Widget\",\"priceAmount\":9.99,"
                          + "\"priceCurrency\":\"USD\",\"active\":true,\"initialQuantity\":10}"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("a duplicate sku is a 409, not a silent second row")
    void duplicateSkuIsConflict() throws Exception {
      String body =
          "{\"sku\":\"SKU-DUP-01\",\"name\":\"Widget\",\"priceAmount\":9.99,"
              + "\"priceCurrency\":\"USD\",\"active\":true,\"initialQuantity\":10}";

      mockMvc
          .perform(
              post("/api/products")
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isCreated());

      mockMvc
          .perform(
              post("/api/products")
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }

    @Test
    @DisplayName("a blank name fails bean validation")
    void blankNameIsValidationFailure() throws Exception {
      mockMvc
          .perform(
              post("/api/products")
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"sku\":\"SKU-NEW-03\",\"name\":\"\",\"priceAmount\":9.99,"
                          + "\"priceCurrency\":\"USD\",\"active\":true}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
  }

  @Nested
  @DisplayName("PUT /api/products/{id} and DELETE /api/products/{id}")
  // See CreateEndpoint's own comment: rolled back after each test so this class's fixture products
  // never leak into ListEndpoint's fixed-count assertions.
  @Transactional
  class UpdateAndDeleteEndpoints {

    private String createProduct() throws Exception {
      String sku = "SKU-" + UUID.randomUUID();
      return mockMvc
          .perform(
              post("/api/products")
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"sku\":\""
                          + sku
                          + "\",\"name\":\"Widget\",\"priceAmount\":9.99,"
                          + "\"priceCurrency\":\"USD\",\"active\":true,\"initialQuantity\":10}"))
          .andReturn()
          .getResponse()
          .getContentAsString();
    }

    @Test
    @DisplayName("a matching version updates the product")
    void updatesWithMatchingVersion() throws Exception {
      String created = createProduct();
      String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");

      mockMvc
          .perform(
              put("/api/products/{id}", id)
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"name\":\"Renamed\",\"priceAmount\":19.99,\"priceCurrency\":\"EUR\","
                          + "\"active\":false,\"version\":0}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.name").value("Renamed"))
          .andExpect(jsonPath("$.priceCurrency").value("EUR"))
          .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("a stale version is a 409, not a silent overwrite")
    void staleVersionIsConflict() throws Exception {
      String created = createProduct();
      String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");

      mockMvc
          .perform(
              put("/api/products/{id}", id)
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"name\":\"Renamed\",\"priceAmount\":19.99,\"priceCurrency\":\"EUR\","
                          + "\"active\":false,\"version\":99}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }

    @Test
    @DisplayName("a VIEWER is forbidden from updating a product")
    void viewerCannotUpdate() throws Exception {
      String created = createProduct();
      String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");

      mockMvc
          .perform(
              put("/api/products/{id}", id)
                  .header(HttpHeaders.AUTHORIZATION, bearerViewer())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"name\":\"Renamed\",\"priceAmount\":19.99,\"priceCurrency\":\"EUR\","
                          + "\"active\":false,\"version\":0}"))
          .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CATALOG_MANAGER can delete a product, and it is then a 404")
    void catalogManagerCanDelete() throws Exception {
      String created = createProduct();
      String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");

      mockMvc
          .perform(
              delete("/api/products/{id}", id)
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager()))
          .andExpect(status().isNoContent());

      mockMvc
          .perform(get("/api/products/{id}", id).header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("a VIEWER is forbidden from deleting a product")
    void viewerCannotDelete() throws Exception {
      String created = createProduct();
      String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");

      mockMvc
          .perform(
              delete("/api/products/{id}", id).header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isForbidden());
    }
  }
}
