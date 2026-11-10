package com.acme.ecommerce.common.core.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FixedTimeProviderTest {

  @Test
  @DisplayName("reports the pinned instant")
  void reportsPinnedInstant() {
    Instant pinned = Instant.parse("2025-03-04T05:06:07Z");

    FixedTimeProvider provider = new FixedTimeProvider(pinned);

    assertThat(provider.now()).isEqualTo(pinned);
    assertThat(provider.today()).isEqualTo(LocalDate.of(2025, 3, 4));
    assertThat(provider.epochMilli()).isEqualTo(pinned.toEpochMilli());
  }

  @Test
  @DisplayName("advanceBy moves the clock forward deterministically")
  void advancesDeterministically() {
    FixedTimeProvider provider = new FixedTimeProvider(Instant.parse("2025-01-01T00:00:00Z"));

    provider.advanceBy(Duration.ofHours(36));

    assertThat(provider.now()).isEqualTo(Instant.parse("2025-01-02T12:00:00Z"));
  }

  @Test
  @DisplayName("system provider is UTC")
  void systemProviderIsUtc() {
    assertThat(new SystemTimeProvider().clock().getZone().getId()).isEqualTo("Z");
  }
}
