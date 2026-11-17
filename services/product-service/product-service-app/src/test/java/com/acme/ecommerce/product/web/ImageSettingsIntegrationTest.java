package com.acme.ecommerce.product.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.ecommerce.product.support.AbstractProductIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The image {@code parentUrl} setting end to end: startup initialisation from the
 * static-image-server endpoint, key resolution with URL encoding on product reads, and the admin
 * CRUD that changes it at runtime.
 */
class ImageSettingsIntegrationTest extends AbstractProductIntegrationTest {

  private static final String PRODUCT_ID = "11111111-1111-4111-8111-111111111111";
  private static final String STARTUP_PARENT = "http://static-image-server.test/product-images";

  @DynamicPropertySource
  static void imageStore(DynamicPropertyRegistry registry) {
    registry.add("image-store.public-url", () -> STARTUP_PARENT + "/");
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;

  private String originalThumbnail;

  @BeforeEach
  void linkKeyedImages() {
    originalThumbnail =
        jdbc.queryForObject(
            "SELECT thumbnail_url FROM product.products WHERE id = ?::uuid",
            String.class,
            PRODUCT_ID);
    jdbc.update(
        "UPDATE product.products SET thumbnail_url = 'output/images/thumb 1.jpg' WHERE id = ?::uuid",
        PRODUCT_ID);
    jdbc.update(
        "INSERT INTO product.product_images (product_id, url, display_order) "
            + "VALUES (?::uuid, 'my dir/a#b.jpg', 0), (?::uuid, 'https://cdn.example.com/x.jpg', 1)",
        PRODUCT_ID,
        PRODUCT_ID);
  }

  @AfterEach
  void restore() throws Exception {
    jdbc.update(
        "DELETE FROM product.product_images WHERE product_id = ?::uuid "
            + "AND url IN ('my dir/a#b.jpg', 'https://cdn.example.com/x.jpg')",
        PRODUCT_ID);
    jdbc.update(
        "UPDATE product.products SET thumbnail_url = ? WHERE id = ?::uuid",
        originalThumbnail,
        PRODUCT_ID);
    mockMvc
        .perform(delete("/api/admin/image-settings").header(HttpHeaders.AUTHORIZATION, admin()))
        .andExpect(status().isOk());
  }

  @Test
  void parentUrlIsInitializedFromTheStaticImageServerOnStartup() throws Exception {
    mockMvc
        .perform(get("/api/products/image-settings"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parentUrl").value(STARTUP_PARENT));
  }

  @Test
  void productImageKeysResolveAgainstParentUrlWithEncoding() throws Exception {
    mockMvc
        .perform(
            get("/api/products/" + PRODUCT_ID).header(HttpHeaders.AUTHORIZATION, bearerViewer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.thumbnailUrl").value(STARTUP_PARENT + "/thumb%201.jpg"))
        .andExpect(jsonPath("$.images[0].url").value(STARTUP_PARENT + "/my%20dir/a%23b.jpg"))
        .andExpect(jsonPath("$.images[1].url").value("https://cdn.example.com/x.jpg"));
  }

  @Test
  void adminCanChangeParentUrlAtRuntime() throws Exception {
    mockMvc
        .perform(
            put("/api/admin/image-settings")
                .header(HttpHeaders.AUTHORIZATION, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentUrl\":\"https://cdn.example.com/product images/\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parentUrl").value("https://cdn.example.com/product%20images"))
        .andExpect(jsonPath("$.updatedAt").isNotEmpty());

    mockMvc
        .perform(
            get("/api/products/" + PRODUCT_ID).header(HttpHeaders.AUTHORIZATION, bearerViewer()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.images[0].url")
                .value("https://cdn.example.com/product%20images/my%20dir/a%23b.jpg"));

    mockMvc
        .perform(delete("/api/admin/image-settings").header(HttpHeaders.AUTHORIZATION, admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parentUrl").value(STARTUP_PARENT));
  }

  @Test
  void invalidParentUrlIsRejected() throws Exception {
    mockMvc
        .perform(
            put("/api/admin/image-settings")
                .header(HttpHeaders.AUTHORIZATION, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentUrl\":\"javascript:alert(1)\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void onlyAdminsMayChangeParentUrl() throws Exception {
    mockMvc
        .perform(
            put("/api/admin/image-settings")
                .header(HttpHeaders.AUTHORIZATION, bearerCatalogManager())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentUrl\":\"https://cdn.example.com/x\"}"))
        .andExpect(status().isForbidden());
  }

  private static String admin() {
    return "Bearer "
        + KEYS.accessToken(
            "8f14e45f-ceea-4e78-9c39-4f5d2b1a0003", "admin@example.com", List.of("ADMIN"));
  }
}
