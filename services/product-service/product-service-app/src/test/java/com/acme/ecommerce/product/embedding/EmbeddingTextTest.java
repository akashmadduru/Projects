package com.acme.ecommerce.product.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmbeddingTextTest {

  @Test
  @DisplayName("builds name, brand, category, description and flattened JSON; no price or rating")
  void buildsText() {
    String text =
        EmbeddingText.build(
            "  Trail  Runner ",
            "Acme",
            "Shoes",
            "Light\nand   breathable",
            "{\"Colour\":\"Blue\",\"Sizes\":[\"8\",\"9\"]}",
            "{\"material\":{\"upper\":\"mesh\"}}");
    assertThat(text)
        .isEqualTo(
            String.join(
                "\n",
                "Name: Trail Runner",
                "Brand: Acme",
                "Category: Shoes",
                "Description: Light and breathable",
                "Colour: Blue",
                "Sizes: 8, 9",
                "material.upper: mesh"));
  }

  @Test
  @DisplayName("missing fields and unreadable JSON contribute nothing instead of failing")
  void tolerant() {
    assertThat(EmbeddingText.build("Lamp", null, null, null, "not json", ""))
        .isEqualTo("Name: Lamp");
  }

  @Test
  @DisplayName("a long description is truncated")
  void truncates() {
    String text = EmbeddingText.build("Lamp", null, null, "x".repeat(5_000), null, null);
    assertThat(text)
        .hasSize("Name: Lamp\nDescription: ".length() + EmbeddingText.MAX_DESCRIPTION_CHARS);
  }

  @Test
  @DisplayName("hash is 64 hex chars, stable, and depends on both the text and the model")
  void hashes() {
    String a = EmbeddingText.hash("Name: Lamp", "m1");
    assertThat(a).matches("[0-9a-f]{64}");
    assertThat(EmbeddingText.hash("Name: Lamp", "m1")).isEqualTo(a);
    assertThat(EmbeddingText.hash("Name: Lamp", "m2")).isNotEqualTo(a);
    assertThat(EmbeddingText.hash("Name: Lamps", "m1")).isNotEqualTo(a);
  }
}
