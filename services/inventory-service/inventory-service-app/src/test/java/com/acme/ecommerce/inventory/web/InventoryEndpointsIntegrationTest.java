package com.acme.ecommerce.inventory.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.ecommerce.inventory.support.AbstractInventoryIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end behaviour of inventory-service's CRUD endpoints, over the real filter chain and a real
 * database.
 *
 * <p>Standalone-by-design for this pass: every test here exercises inventory-service on its own -
 * no Kafka, no product-service - because there is no producer/consumer wiring yet. Mirrors {@code
 * ProductEndpointsIntegrationTest}'s shape.
 */
class InventoryEndpointsIntegrationTest extends AbstractInventoryIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Nested
  @DisplayName("token validation")
  class TokenValidation {

    @Test
    @DisplayName("no token is rejected with a 401 problem+json document")
    void anonymousIsRejected() throws Exception {
      mockMvc
          .perform(get("/api/inventory"))
          .andExpect(status().isUnauthorized())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
          .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("a validly-signed token with the platform's issuer and audience is accepted")
    void validTokenIsAccepted() throws Exception {
      mockMvc
          .perform(get("/api/inventory").header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a token minted for a different relying party is rejected")
    void wrongAudienceIsRejected() throws Exception {
      mockMvc
          .perform(
              get("/api/inventory")
                  .header(
                      HttpHeaders.AUTHORIZATION, "Bearer " + KEYS.wrongAudienceToken("someone")))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
  }

  @Nested
  @DisplayName("GET /api/inventory")
  class ListEndpoint {

    @Test
    @DisplayName("returns an empty page against a fresh database, in the PagedModel envelope")
    void returnsEmptyPageInitially() throws Exception {
      mockMvc
          .perform(get("/api/inventory").header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isOk())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(jsonPath("$.page.size").value(20));
    }
  }

  @Nested
  @DisplayName("POST /api/inventory")
  class CreateEndpoint {

    @Test
    @DisplayName("CATALOG_MANAGER can provision a stock-level row")
    void catalogManagerCanCreate() throws Exception {
      UUID productId = UUID.randomUUID();
      mockMvc
          .perform(
              post("/api/inventory")
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"productId\":\"" + productId + "\",\"quantity\":10}"))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.productId").value(productId.toString()))
          .andExpect(jsonPath("$.quantity").value(10))
          .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    @DisplayName("a VIEWER is forbidden from creating a stock-level row")
    void viewerCannotCreate() throws Exception {
      mockMvc
          .perform(
              post("/api/inventory")
                  .header(HttpHeaders.AUTHORIZATION, bearerViewer())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":10}"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("a duplicate productId is a 409, not a silent second row")
    void duplicateProductIdIsConflict() throws Exception {
      UUID productId = UUID.randomUUID();
      String body = "{\"productId\":\"" + productId + "\",\"quantity\":10}";

      mockMvc
          .perform(
              post("/api/inventory")
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isCreated());

      mockMvc
          .perform(
              post("/api/inventory")
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }

    @Test
    @DisplayName("a negative quantity fails bean validation, not the database CHECK constraint")
    void negativeQuantityIsValidationFailure() throws Exception {
      mockMvc
          .perform(
              post("/api/inventory")
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":-1}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
  }

  @Nested
  @DisplayName("PUT /api/inventory/{id} and DELETE /api/inventory/{id}")
  class UpdateAndDeleteEndpoints {

    private String createRow() throws Exception {
      UUID productId = UUID.randomUUID();
      return mockMvc
          .perform(
              post("/api/inventory")
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"productId\":\"" + productId + "\",\"quantity\":5}"))
          .andReturn()
          .getResponse()
          .getContentAsString();
    }

    @Test
    @DisplayName("a matching version updates the quantity")
    void updatesWithMatchingVersion() throws Exception {
      String created = createRow();
      String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");

      mockMvc
          .perform(
              put("/api/inventory/{id}", id)
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"quantity\":99,\"version\":0}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.quantity").value(99))
          .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    @DisplayName("a stale version is a 409, not a silent overwrite")
    void staleVersionIsConflict() throws Exception {
      String created = createRow();
      String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");

      mockMvc
          .perform(
              put("/api/inventory/{id}", id)
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"quantity\":99,\"version\":99}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
    }

    @Test
    @DisplayName("CATALOG_MANAGER can delete a row, and it is then a 404")
    void catalogManagerCanDelete() throws Exception {
      String created = createRow();
      String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");

      mockMvc
          .perform(
              delete("/api/inventory/{id}", id)
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager()))
          .andExpect(status().isNoContent());

      mockMvc
          .perform(get("/api/inventory/{id}", id).header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("a VIEWER is forbidden from deleting a row")
    void viewerCannotDelete() throws Exception {
      String created = createRow();
      String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");

      mockMvc
          .perform(
              delete("/api/inventory/{id}", id).header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isForbidden());
    }
  }

  @Nested
  @DisplayName("GET /api/inventory/{id}")
  class ByIdEndpoint {

    @Test
    @DisplayName("an unknown id is a 404 problem+json document, not an empty 200")
    void unknownIdIsProblemJson() throws Exception {
      mockMvc
          .perform(
              get("/api/inventory/{id}", "00000000-0000-4000-8000-0000000000ff")
                  .header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isNotFound())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
          .andExpect(jsonPath("$.resourceType").value("Inventory"));
    }

    @Test
    @DisplayName("a non-UUID id is a 400, not a 500")
    void malformedIdIsBadRequest() throws Exception {
      mockMvc
          .perform(
              get("/api/inventory/{id}", "not-a-uuid")
                  .header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
  }

  @Nested
  @DisplayName("GET /api/inventory/by-product/{productId}")
  class ByProductIdEndpoint {

    @Test
    @DisplayName("returns the row for a provisioned productId")
    void returnsRowWhenProvisioned() throws Exception {
      UUID productId = UUID.randomUUID();
      mockMvc
          .perform(
              post("/api/inventory")
                  .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"productId\":\"" + productId + "\",\"quantity\":15}"))
          .andExpect(status().isCreated());

      mockMvc
          .perform(
              get("/api/inventory/by-product/{productId}", productId)
                  .header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.productId").value(productId.toString()))
          .andExpect(jsonPath("$.quantity").value(15));
    }

    @Test
    @DisplayName("an unprovisioned productId is a 404 problem+json document, not an empty 200")
    void unprovisionedProductIdIsProblemJson() throws Exception {
      mockMvc
          .perform(
              get("/api/inventory/by-product/{productId}", UUID.randomUUID())
                  .header(HttpHeaders.AUTHORIZATION, bearerViewer()))
          .andExpect(status().isNotFound())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
          .andExpect(jsonPath("$.resourceType").value("Inventory"));
    }

    @Test
    @DisplayName("no token is rejected with a 401, same as every other read")
    void anonymousIsRejected() throws Exception {
      mockMvc
          .perform(get("/api/inventory/by-product/{productId}", UUID.randomUUID()))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
  }
}
