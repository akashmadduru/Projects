package com.acme.ecommerce.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {

  private final AudienceValidator validator = new AudienceValidator("product-service");

  @Test
  @DisplayName("accepts a token whose audience contains the expected value")
  void acceptsMatchingAudience() {
    OAuth2TokenValidatorResult result =
        validator.validate(jwtWithAudience(List.of("product-service", "gateway")));

    assertThat(result.hasErrors()).isFalse();
  }

  @Test
  @DisplayName("rejects a token minted for a different service")
  void rejectsForeignAudience() {
    // The replay scenario this validator exists to stop: a valid, correctly-signed token issued for
    // another service must not be accepted here.
    OAuth2TokenValidatorResult result =
        validator.validate(jwtWithAudience(List.of("auth-service")));

    assertThat(result.hasErrors()).isTrue();
  }

  @Test
  @DisplayName("rejects a token with no audience claim")
  void rejectsMissingAudience() {
    assertThat(validator.validate(jwtWithAudience(null)).hasErrors()).isTrue();
  }

  @Test
  @DisplayName("the failure message does not disclose the token's actual audience")
  void doesNotLeakActualAudience() {
    OAuth2TokenValidatorResult result =
        validator.validate(jwtWithAudience(List.of("internal-billing-service")));

    assertThat(result.getErrors())
        .allSatisfy(error -> assertThat(error.getDescription()).doesNotContain("internal-billing"));
  }

  private static Jwt jwtWithAudience(List<String> audience) {
    Jwt.Builder builder =
        Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject("user-1")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300));
    if (audience != null) {
      builder.audience(audience);
    }
    return builder.build();
  }
}
