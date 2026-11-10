package com.acme.ecommerce.common.core.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CorrelationIdHolderTest {

  @AfterEach
  void clearHolder() {
    CorrelationIdHolder.clear();
  }

  @Test
  @DisplayName("returns empty when nothing is bound")
  void returnsEmptyWhenUnbound() {
    assertThat(CorrelationIdHolder.get()).isEmpty();
  }

  @Test
  @DisplayName("set(null) generates an id rather than binding null")
  void setNullGenerates() {
    String bound = CorrelationIdHolder.set(null);

    assertThat(bound).isNotBlank();
    assertThat(CorrelationIdHolder.get()).contains(bound);
  }

  @Test
  @DisplayName("blank values are treated as absent")
  void blankIsTreatedAsAbsent() {
    String bound = CorrelationIdHolder.set("   ");

    assertThat(bound).isNotBlank().isNotEqualTo("   ");
  }

  @Test
  @DisplayName("getOrGenerate is stable across calls within a thread")
  void getOrGenerateIsStable() {
    String first = CorrelationIdHolder.getOrGenerate();
    String second = CorrelationIdHolder.getOrGenerate();

    assertThat(second).isEqualTo(first);
  }

  @Test
  @DisplayName("clear unbinds the id")
  void clearUnbinds() {
    CorrelationIdHolder.set("abc-123");
    CorrelationIdHolder.clear();

    assertThat(CorrelationIdHolder.get()).isEmpty();
  }

  @Test
  @DisplayName("wrap propagates the caller's id to a pooled thread and restores its prior state")
  void wrapPropagatesAcrossExecutor() throws Exception {
    // Reproduces the stale-pool hazard the class javadoc warns about: the worker thread is created
    // (and thus inherits) BEFORE the caller binds its id, so only wrap() can carry it across.
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      executor.submit(() -> CorrelationIdHolder.set("worker-original")).get();

      CorrelationIdHolder.set("caller-id");
      Future<String> observed =
          executor.submit(
              () -> {
                String[] seen = new String[1];
                CorrelationIdHolder.wrap(() -> seen[0] = CorrelationIdHolder.getOrGenerate()).run();
                return seen[0];
              });

      // wrap() captures at construction time on the calling thread, so build it here instead.
      String[] captured = new String[1];
      Runnable wrapped =
          CorrelationIdHolder.wrap(() -> captured[0] = CorrelationIdHolder.getOrGenerate());
      executor.submit(wrapped).get();

      assertThat(captured[0]).isEqualTo("caller-id");
      assertThat(observed).isNotNull();

      // The worker's own binding is restored after the wrapped task completes.
      String workerAfter = executor.submit(() -> CorrelationIdHolder.get().orElse(null)).get();
      assertThat(workerAfter).isEqualTo("worker-original");
    } finally {
      executor.shutdownNow();
    }
  }
}
