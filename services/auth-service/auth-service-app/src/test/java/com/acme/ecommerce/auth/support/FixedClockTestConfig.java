package com.acme.ecommerce.auth.support;

import com.acme.ecommerce.common.core.time.FixedTimeProvider;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Replaces the platform clock so window and lockout expiry can be advanced deterministically. */
@TestConfiguration
public class FixedClockTestConfig {

  /** Aligned to a UTC day and to every window used by the OTP caps. */
  public static final Instant START = Instant.parse("2025-01-01T00:00:00Z");

  @Bean
  public FixedTimeProvider fixedTimeProvider() {
    return new FixedTimeProvider(START);
  }
}
