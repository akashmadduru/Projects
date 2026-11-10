package com.acme.ecommerce.common.core.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * The platform's single source of "now".
 *
 * <p>Direct calls to {@code Instant.now()} / {@code LocalDate.now()} scattered through domain code
 * are the classic reason time-dependent logic (expiry, scheduling, audit windows) can only be
 * tested with {@code Thread.sleep} or not at all. Injecting this interface makes those paths
 * deterministically testable via {@link FixedTimeProvider}.
 *
 * <p>Everything is UTC. Timezone conversion is a presentation concern and belongs at the edge, not
 * in stored values.
 */
public interface TimeProvider {

  /** The underlying clock, for APIs that take a {@link Clock} directly. */
  Clock clock();

  /** Current instant, UTC. */
  default Instant now() {
    return Instant.now(clock());
  }

  /** Current date-time, UTC - suitable for JPA {@code LocalDateTime} columns. */
  default LocalDateTime nowDateTime() {
    return LocalDateTime.ofInstant(now(), ZoneOffset.UTC);
  }

  /** Current date, UTC. */
  default LocalDate today() {
    return LocalDate.ofInstant(now(), ZoneOffset.UTC);
  }

  /** Current epoch milliseconds. */
  default long epochMilli() {
    return clock().millis();
  }
}
