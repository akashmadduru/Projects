package com.acme.ecommerce.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccessTokenDenyListTest {

  private static final long CUTOFF = 1_700_000_000L;

  @Test
  void iatBeforeCutoffIsRevoked() {
    assertThat(AccessTokenDenyList.revoked(Instant.ofEpochSecond(CUTOFF - 1), CUTOFF)).isTrue();
  }

  @Test
  void iatEqualToCutoffIsRevoked() {
    assertThat(AccessTokenDenyList.revoked(Instant.ofEpochSecond(CUTOFF), CUTOFF)).isTrue();
    assertThat(AccessTokenDenyList.revoked(Instant.ofEpochSecond(CUTOFF, 900_000_000), CUTOFF))
        .isTrue();
  }

  @Test
  void iatAfterCutoffIsAccepted() {
    assertThat(AccessTokenDenyList.revoked(Instant.ofEpochSecond(CUTOFF + 1), CUTOFF)).isFalse();
  }

  @Test
  void nullIatIsRevoked() {
    assertThat(AccessTokenDenyList.revoked(null, CUTOFF)).isTrue();
  }

  @Test
  void keyFormat() {
    assertThat(AccessTokenDenyList.key("abc")).isEqualTo("access-deny:abc");
  }

  @Test
  void nullAndBlankSubAreRejected() {
    assertThatThrownBy(() -> AccessTokenDenyList.key(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccessTokenDenyList.key(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccessTokenDenyList.key(""))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
