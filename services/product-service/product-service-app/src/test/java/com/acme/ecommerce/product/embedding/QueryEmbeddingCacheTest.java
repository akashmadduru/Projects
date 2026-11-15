package com.acme.ecommerce.product.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.common.core.time.FixedTimeProvider;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueryEmbeddingCacheTest {

  private final FixedTimeProvider time = FixedTimeProvider.atEpoch();

  @Test
  @DisplayName("returns what was stored until the TTL passes")
  void ttl() {
    QueryEmbeddingCache cache = new QueryEmbeddingCache(10, Duration.ofMinutes(5), time);
    cache.put("k", new float[] {1f});
    assertThat(cache.get("k")).isPresent();
    time.advanceBy(Duration.ofMinutes(4));
    assertThat(cache.get("k")).isPresent();
    time.advanceBy(Duration.ofMinutes(2));
    assertThat(cache.get("k")).isEmpty();
    assertThat(cache.size()).isZero();
  }

  @Test
  @DisplayName("evicts the least recently USED entry when full")
  void lru() {
    QueryEmbeddingCache cache = new QueryEmbeddingCache(2, Duration.ofMinutes(5), time);
    cache.put("a", new float[] {1f});
    cache.put("b", new float[] {2f});
    cache.get("a"); // a is now the most recently used
    cache.put("c", new float[] {3f});
    assertThat(cache.get("a")).isPresent();
    assertThat(cache.get("b")).isEmpty();
    assertThat(cache.get("c")).isPresent();
  }
}
