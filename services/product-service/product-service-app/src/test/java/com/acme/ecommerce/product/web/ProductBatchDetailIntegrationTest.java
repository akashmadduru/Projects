package com.acme.ecommerce.product.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.ecommerce.product.support.AbstractProductIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/products/batch}: the lean default is unchanged, and {@code detail=true} returns
 * the {@code GET /{id}} view (F-20261119-11 / TD-BE-06).
 */
class ProductBatchDetailIntegrationTest extends AbstractProductIntegrationTest {

  private static final String KEYBOARD = "11111111-1111-4111-8111-111111111111";
  private static final String MOUSE = "22222222-2222-4222-8222-222222222222";

  /** Seeded with active = false. */
  private static final String HEADSET = "55555555-5555-4555-8555-555555555555";

  @Autowired private MockMvc mockMvc;

  private String body(String url, String... params) throws Exception {
    var request = get(url);
    for (int i = 0; i < params.length; i += 2) {
      request = request.param(params[i], params[i + 1]);
    }
    return mockMvc
        .perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("the lean default response is byte-identical to the pre-change golden")
  void leanDefaultIsByteIdentical() throws Exception {
    String golden =
        new ClassPathResource("golden/batch-lean-seed.json")
            .getContentAsString(StandardCharsets.UTF_8);
    // Request order, a duplicate and an inactive product: the inactive one is dropped, the
    // duplicate collapsed.
    String ids = String.join(",", HEADSET, MOUSE, KEYBOARD, MOUSE);

    assertThat(body("/api/products/batch", "ids", ids)).isEqualTo(golden);
    // An explicit detail=false is the same default.
    assertThat(body("/api/products/batch", "ids", ids, "detail", "false")).isEqualTo(golden);
    // Sanity for the golden itself: it holds the two active products and not the inactive one.
    assertThat(golden).contains("SKU-MOUSE-01").contains("SKU-KEYBOARD-01");
    assertThat(golden).doesNotContain("SKU-HEADSET-01");
  }

  @Test
  @DisplayName("detail=true: each element is exactly the GET /{id} detail shape")
  void detailParityWithFindById() throws Exception {
    List<String> singles = new ArrayList<>();
    for (String id : List.of(HEADSET, MOUSE, KEYBOARD)) {
      singles.add(body("/api/products/" + id));
    }
    String expected = singles.stream().collect(Collectors.joining(",", "[", "]"));

    String actual =
        body(
            "/api/products/batch",
            "ids",
            String.join(",", HEADSET, MOUSE, KEYBOARD, MOUSE),
            "detail",
            "true");

    assertThat(actual).isEqualTo(expected);
    // Sanity: the detail shape really differs from lean (it carries the variants array).
    assertThat(actual).contains("\"variants\"").contains("SKU-HEADSET-01");
  }

  @Test
  @DisplayName("detail=true keeps request order, includes inactive and collapses duplicates")
  void detailOrderInactiveAndDuplicates() throws Exception {
    mockMvc
        .perform(
            get("/api/products/batch")
                .param("ids", String.join(",", HEADSET, KEYBOARD, HEADSET, MOUSE))
                .param("detail", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value(HEADSET))
        .andExpect(jsonPath("$[0].active").value(false))
        .andExpect(jsonPath("$[1].id").value(KEYBOARD))
        .andExpect(jsonPath("$[2].id").value(MOUSE));
  }

  @Test
  @DisplayName("detail=true skips unknown ids; an empty list gives an empty array")
  void detailUnknownAndEmpty() throws Exception {
    String unknown = UUID.randomUUID().toString();
    mockMvc
        .perform(
            get("/api/products/batch").param("ids", unknown + "," + MOUSE).param("detail", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(MOUSE));
    assertThat(body("/api/products/batch", "ids", "", "detail", "true")).isEqualTo("[]");
    assertThat(body("/api/products/batch", "ids", "")).isEqualTo("[]");
  }

  @Test
  @DisplayName("the caps are unchanged: 51 ids and a non-UUID are 400, 50 ids are fine")
  void capsUnchanged() throws Exception {
    for (String detail : List.of("false", "true")) {
      String fifty =
          java.util.stream.IntStream.range(0, 50)
              .mapToObj(i -> UUID.randomUUID().toString())
              .collect(Collectors.joining(","));
      mockMvc
          .perform(get("/api/products/batch").param("ids", fifty).param("detail", detail))
          .andExpect(status().isOk());
      mockMvc
          .perform(
              get("/api/products/batch")
                  .param("ids", fifty + "," + UUID.randomUUID())
                  .param("detail", detail))
          .andExpect(status().isBadRequest());
      mockMvc
          .perform(get("/api/products/batch").param("ids", "not-a-uuid").param("detail", detail))
          .andExpect(status().isBadRequest());
    }
  }
}
