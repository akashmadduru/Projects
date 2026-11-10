package com.acme.ecommerce.common.core.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Mutable, controllable {@link TimeProvider} for tests.
 *
 * <p>Lives in main (not test) sources deliberately: every service module's tests need it, and
 * publishing a test-fixtures variant of common-core for one small class is more build machinery
 * than the class is worth.
 *
 * <p>Not thread-safe by design - tests drive it from a single thread.
 */
public final class FixedTimeProvider implements TimeProvider {

  private Clock clock;

  public FixedTimeProvider(Instant fixedAt) {
    this.clock = Clock.fixed(Objects.requireNonNull(fixedAt, "fixedAt"), ZoneOffset.UTC);
  }

  /** Pins time to a fixed, arbitrary-but-stable instant. */
  public static FixedTimeProvider atEpoch() {
    return new FixedTimeProvider(Instant.parse("2025-01-01T00:00:00Z"));
  }

  @Override
  public Clock clock() {
    return clock;
  }

  /** Moves the clock forward (or back, with a negative duration). */
  public FixedTimeProvider advanceBy(Duration duration) {
    this.clock = Clock.offset(clock, duration);
    return this;
  }

  /** Jumps the clock to an absolute instant. */
  public FixedTimeProvider setTo(Instant instant) {
    this.clock = Clock.fixed(Objects.requireNonNull(instant, "instant"), ZoneOffset.UTC);
    return this;
  }
}
