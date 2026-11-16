package com.acme.ecommerce.product.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.product.embedding.adapter.FakeEmbeddingPort;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FakeEmbeddingPortTest {

  private final FakeEmbeddingPort port = new FakeEmbeddingPort(EmbeddingSchema.DIMS);

  private static double cosine(float[] a, float[] b) {
    double dot = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
    }
    return dot;
  }

  @Test
  @DisplayName("deterministic, unit length, the configured width")
  void deterministicAndNormalised() {
    float[] first = port.embed(List.of("wireless headphones"), EmbedKind.DOCUMENT).get(0);
    float[] second = port.embed(List.of("wireless headphones"), EmbedKind.QUERY).get(0);
    assertThat(first).hasSize(EmbeddingSchema.DIMS).containsExactly(second);
    assertThat(cosine(first, first)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
  }

  @Test
  @DisplayName("texts sharing words are closer than texts sharing none")
  void sharedWordsAreCloser() {
    List<float[]> v =
        port.embed(
            List.of("wireless noise cancelling headphones", "wireless headphones", "cast iron pan"),
            EmbedKind.DOCUMENT);
    assertThat(cosine(v.get(0), v.get(1))).isGreaterThan(cosine(v.get(0), v.get(2)) + 0.3);
  }
}
