package com.acme.ecommerce.common.core.correlation;

import java.util.Optional;
import java.util.UUID;

/**
 * Request-scoped holder for the correlation id that ties log lines together across services.
 *
 * <p>Backed by an {@link InheritableThreadLocal} so work handed to a child thread inherits the id.
 *
 * <p>THREAD-POOL CAVEAT: inheritance happens at thread <em>creation</em>, not at task submission. A
 * pooled executor therefore keeps whatever id its threads were born with, which goes stale. For any
 * async hand-off, capture the id explicitly and use {@link #wrap(Runnable)} rather than relying on
 * inheritance.
 *
 * <p>VIRTUAL THREADS: each task gets a fresh virtual thread, so inheritance behaves correctly
 * there. When this platform moves request handling onto virtual threads (Boot 4 / {@code
 * spring.threads.virtual.enabled}), consider migrating to a {@code ScopedValue}, which is both
 * cheaper and immune to the stale-pool problem. Left as a ThreadLocal for now because the servlet
 * filter contract requires mutation mid-request.
 *
 * <p>Callers MUST {@link #clear()} in a {@code finally} block; the servlet filter in common-web
 * does this. Failing to clear leaks the id onto the next request served by that pooled thread.
 */
public final class CorrelationIdHolder {

  /** Canonical header name used on every inbound and outbound HTTP hop. */
  public static final String HEADER_NAME = "X-Correlation-Id";

  /** SLF4J MDC key; referenced by the logging patterns in each service's logback config. */
  public static final String MDC_KEY = "correlationId";

  private static final ThreadLocal<String> HOLDER = new InheritableThreadLocal<>();

  private CorrelationIdHolder() {
    throw new AssertionError("static utility");
  }

  /** Returns the current correlation id, if one has been bound to this thread. */
  public static Optional<String> get() {
    return Optional.ofNullable(HOLDER.get());
  }

  /**
   * Returns the current correlation id, generating and binding a fresh one if absent.
   *
   * <p>Used by error handling, where an id must always be present to quote back to the caller even
   * if the failure happened before/outside the filter chain.
   */
  public static String getOrGenerate() {
    String existing = HOLDER.get();
    if (existing != null) {
      return existing;
    }
    String generated = generate();
    HOLDER.set(generated);
    return generated;
  }

  /** Binds an id to the current thread. A null or blank value binds a freshly generated id. */
  public static String set(String correlationId) {
    String value =
        correlationId == null || correlationId.isBlank() ? generate() : correlationId.trim();
    HOLDER.set(value);
    return value;
  }

  /** Unbinds the id. Always call this in a {@code finally} block on pooled threads. */
  public static void clear() {
    HOLDER.remove();
  }

  /** Generates a new correlation id. */
  public static String generate() {
    return UUID.randomUUID().toString();
  }

  /**
   * Wraps a task so it runs with the caller's correlation id bound, restoring the worker thread's
   * previous state afterwards. Use this whenever work is handed to an {@link
   * java.util.concurrent.Executor}.
   */
  public static Runnable wrap(Runnable task) {
    String captured = getOrGenerate();
    return () -> {
      String previous = HOLDER.get();
      HOLDER.set(captured);
      try {
        task.run();
      } finally {
        if (previous == null) {
          HOLDER.remove();
        } else {
          HOLDER.set(previous);
        }
      }
    };
  }
}
