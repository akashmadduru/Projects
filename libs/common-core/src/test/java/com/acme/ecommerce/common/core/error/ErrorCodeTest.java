package com.acme.ecommerce.common.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

  @Test
  @DisplayName("the referral refusal codes are 422 with a stable code string")
  void referralCodes() {
    assertThat(ErrorCode.REFERRAL_CODE_TOO_NEW.code()).isEqualTo("REFERRAL_CODE_TOO_NEW");
    assertThat(ErrorCode.REFERRAL_CODE_TOO_NEW.status()).isEqualTo(422);
    assertThat(ErrorCode.REFERRAL_IP_MATCH.code()).isEqualTo("REFERRAL_IP_MATCH");
    assertThat(ErrorCode.REFERRAL_IP_MATCH.status()).isEqualTo(422);
    assertThat(ErrorCode.REFERRAL_IP_MATCH.type())
        .isEqualTo("https://errors.acme-ecommerce.internal/referral-ip-match");
  }

  @Test
  @DisplayName("every code string is unique and equals its enum name")
  void codesUnique() {
    assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::code)).doesNotHaveDuplicates();
    for (ErrorCode c : ErrorCode.values()) {
      assertThat(c.code()).isEqualTo(c.name());
    }
  }
}
