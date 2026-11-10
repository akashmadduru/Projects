package com.acme.ecommerce.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The per-account lockout policy - the platform's real brute-force control. */
class UserCredentialTest {

  private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
  private static final Duration BASE = Duration.ofMinutes(1);
  private static final Duration MAX = Duration.ofMinutes(15);
  private static final int THRESHOLD = 5;

  private static UserCredential credential() {
    return UserCredential.create(UUID.randomUUID(), "{argon2id}$argon2id$v=19$fake", NOW);
  }

  @Test
  @DisplayName("failures below the threshold count but do not lock")
  void countsWithoutLockingBelowThreshold() {
    UserCredential credential = credential();

    for (int i = 1; i < THRESHOLD; i++) {
      assertThat(credential.recordFailure(THRESHOLD, BASE, MAX, NOW)).isFalse();
      assertThat(credential.getFailedAttempts()).isEqualTo((short) i);
      assertThat(credential.isLockedAt(NOW)).isFalse();
    }
  }

  @Test
  @DisplayName("the lock backs off exponentially and is capped")
  void backsOffExponentiallyAndCaps() {
    UserCredential credential = credential();
    for (int i = 0; i < THRESHOLD; i++) {
      credential.recordFailure(THRESHOLD, BASE, MAX, NOW);
    }
    // 5th failure = base.
    assertThat(credential.getLockedUntil()).isEqualTo(NOW.plus(BASE));

    credential.recordFailure(THRESHOLD, BASE, MAX, NOW);
    assertThat(credential.getLockedUntil()).isEqualTo(NOW.plus(BASE.multipliedBy(2)));

    credential.recordFailure(THRESHOLD, BASE, MAX, NOW);
    assertThat(credential.getLockedUntil()).isEqualTo(NOW.plus(BASE.multipliedBy(4)));

    // Keep going: the cap must hold, otherwise an attacker can lock a real user
    // out permanently just by continuing to guess.
    for (int i = 0; i < 40; i++) {
      credential.recordFailure(THRESHOLD, BASE, MAX, NOW);
    }
    assertThat(credential.getLockedUntil()).isEqualTo(NOW.plus(MAX));
  }

  @Test
  @DisplayName("the lock expires on its own")
  void lockExpires() {
    UserCredential credential = credential();
    for (int i = 0; i < THRESHOLD; i++) {
      credential.recordFailure(THRESHOLD, BASE, MAX, NOW);
    }

    assertThat(credential.isLockedAt(NOW.plusSeconds(30))).isTrue();
    assertThat(credential.isLockedAt(NOW.plus(BASE).plusSeconds(1))).isFalse();
  }

  @Test
  @DisplayName("success clears both counters")
  void successClearsCounters() {
    UserCredential credential = credential();
    for (int i = 0; i < THRESHOLD; i++) {
      credential.recordFailure(THRESHOLD, BASE, MAX, NOW);
    }

    credential.recordSuccess();

    assertThat(credential.getFailedAttempts()).isZero();
    assertThat(credential.getLockedUntil()).isNull();
  }

  @Test
  @DisplayName("the algorithm column is derived from the hash prefix, so the two cannot drift")
  void derivesAlgorithmFromHashPrefix() {
    assertThat(credential().getAlgorithm()).isEqualTo("argon2id");
    assertThat(UserCredential.create(UUID.randomUUID(), "{bcrypt}$2a$10$abc", NOW).getAlgorithm())
        .isEqualTo("bcrypt");
    assertThat(UserCredential.create(UUID.randomUUID(), "no-prefix", NOW).getAlgorithm())
        .isEqualTo("unknown");
  }

  @Test
  @DisplayName("toString never renders the password hash")
  void toStringMasksTheHash() {
    assertThat(credential().toString()).doesNotContain("argon2id$v=19$fake").contains("***");
  }
}
