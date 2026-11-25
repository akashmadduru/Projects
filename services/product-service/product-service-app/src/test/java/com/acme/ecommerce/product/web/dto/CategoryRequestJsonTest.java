package com.acme.ecommerce.product.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** TD-ADM-09: category requests bind {@code active}, and still accept {@code isActive}. */
class CategoryRequestJsonTest {

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void createBindsBothSpellings() throws Exception {
    assertThat(
            json.readValue("{\"name\":\"a\",\"active\":false}", CreateCategoryRequest.class)
                .getIsActive())
        .isFalse();
    assertThat(
            json.readValue("{\"name\":\"a\",\"isActive\":false}", CreateCategoryRequest.class)
                .getIsActive())
        .isFalse();
    assertThat(json.readValue("{\"name\":\"a\"}", CreateCategoryRequest.class).getIsActive())
        .isNull();
  }

  @Test
  void updateBindsBothSpellings() throws Exception {
    assertThat(
            json.readValue(
                    "{\"name\":\"a\",\"active\":true,\"version\":1}", UpdateCategoryRequest.class)
                .isActive())
        .isTrue();
    assertThat(
            json.readValue(
                    "{\"name\":\"a\",\"isActive\":true,\"version\":1}", UpdateCategoryRequest.class)
                .isActive())
        .isTrue();
  }
}
