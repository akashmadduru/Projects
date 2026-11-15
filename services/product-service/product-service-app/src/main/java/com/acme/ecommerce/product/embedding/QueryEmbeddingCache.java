package com.acme.ecommerce.product.embedding;

import com.acme.ecommerce.common.core.time.TimeProvider;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Small in-process LRU + TTL cache of query vectors, so a repeated query costs no provider call.
 *
 * <p>Hand-rolled rather than a Caffeine dependency: the need is one map with an access-order
 * eviction rule, and taking the clock from {@link TimeProvider} keeps expiry deterministic in
 * tests. All access is synchronized; the critical sections are a single map operation.
 */
public final class QueryEmbeddingCache {

  private record Cached(float[] vector, long expiresAtMillis) {}

  private final Duration ttl;
  private final TimeProvider time;
  private final Map<String, Cached> entries;

  public QueryEmbeddingCache(int maxEntries, Duration ttl, TimeProvider time) {
    this.ttl = ttl;
    this.time = time;
    this.entries =
        new LinkedHashMap<>(16, 0.75f, true) {
          private static final long serialVersionUID = 1L;

          @Override
          protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > maxEntries;
          }
        };
  }

  /** The cached vector for the key, unless absent or expired. */
  public synchronized Optional<float[]> get(String key) {
    Cached entry = entries.get(key);
    if (entry == null) {
      return Optional.empty();
    }
    if (entry.expiresAtMillis() <= time.epochMilli()) {
      entries.remove(key);
      return Optional.empty();
    }
    return Optional.of(entry.vector());
  }

  public synchronized void put(String key, float[] vector) {
    entries.put(key, new Cached(vector, time.epochMilli() + ttl.toMillis()));
  }

  public synchronized void clear() {
    entries.clear();
  }

  public synchronized int size() {
    return entries.size();
  }
}
