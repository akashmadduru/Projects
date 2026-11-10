package com.acme.ecommerce.common.core.time;

import java.time.Clock;

/**
 * Production {@link TimeProvider}, backed by the system UTC clock.
 *
 * <p>Register as a bean; services should inject {@code TimeProvider}, never this class.
 */
public final class SystemTimeProvider implements TimeProvider {

  private final Clock clock = Clock.systemUTC();

  @Override
  public Clock clock() {
    return clock;
  }
}
