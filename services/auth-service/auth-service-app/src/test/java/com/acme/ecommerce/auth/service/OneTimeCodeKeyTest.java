package com.acme.ecommerce.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** TD-P6-09: one-time codes are hashed under a key derived from AUTH_DATA_KEY. */
class OneTimeCodeKeyTest {

  private static final String KEY_A = Base64.getEncoder().encodeToString(new byte[32]);
  private static final String KEY_B;

  static {
    byte[] b = new byte[32];
    b[0] = 1;
    KEY_B = Base64.getEncoder().encodeToString(b);
  }

  @Test
  void blankDataKeyMeansNoCodeKey() {
    assertThat(OneTimeTokenService.deriveCodeKey(null)).isNull();
    assertThat(OneTimeTokenService.deriveCodeKey("  ")).isNull();
  }

  @Test
  void derivedKeyIsStableAndDiffersFromTheDataKey() {
    SecretKeySpec a1 = OneTimeTokenService.deriveCodeKey(KEY_A);
    SecretKeySpec a2 = OneTimeTokenService.deriveCodeKey(KEY_A);
    SecretKeySpec b = OneTimeTokenService.deriveCodeKey(KEY_B);
    assertThat(a1.getEncoded()).isEqualTo(a2.getEncoded());
    assertThat(a1.getEncoded()).isNotEqualTo(b.getEncoded());
    // The 2FA cipher key is never reused as-is for code hashing.
    assertThat(a1.getEncoded()).isNotEqualTo(Base64.getDecoder().decode(KEY_A));
    assertThat(a1.getAlgorithm()).isEqualTo("HmacSHA256");
  }
}
