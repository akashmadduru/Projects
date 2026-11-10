package com.acme.ecommerce.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.auth.api.dto.AuthMethod;
import com.acme.ecommerce.auth.api.dto.LoginRequest;
import com.acme.ecommerce.auth.api.dto.RegisterRequest;
import com.acme.ecommerce.auth.api.dto.TokenResponse;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards on the PUBLISHED contract.
 *
 * <p>Everything asserted here is something a consumer (the admin SPA, the gateway) would break on
 * if it changed, and none of it is protected by the compiler.
 */
class AuthContractTest {

  @Test
  @DisplayName("no DTO leaks a credential through toString")
  void credentialsAreMaskedInToString() {
    // A single log.debug("{}", request) is all it takes for a plaintext password
    // to land in log aggregation, where it is effectively permanent.
    assertThat(new LoginRequest("a@b.com", "hunter2").toString())
        .contains("a@b.com")
        .doesNotContain("hunter2");
    assertThat(new RegisterRequest("a@b.com", "hunter2", "A").toString()).doesNotContain("hunter2");
    assertThat(TokenResponse.bearer("ey.the.token", 600, AuthMethod.PASSWORD).toString())
        .doesNotContain("ey.the.token");
  }

  @Test
  @DisplayName("auth-method wire values are the lower-case forms, not the enum names")
  void authMethodWireValues() {
    // These strings appear in the `auth_method` JWT claim and in the database
    // CHECK constraint. Renaming one is a breaking change for every consumer.
    assertThat(AuthMethod.PASSWORD.claimValue()).isEqualTo("password");
    assertThat(AuthMethod.GOOGLE.claimValue()).isEqualTo("google");
    assertThat(AuthMethod.fromClaimValue("GOOGLE")).isEqualTo(AuthMethod.GOOGLE);
    assertThatThrownBy(() -> AuthMethod.fromClaimValue("facebook"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("every error code has a unique, stable code and a sane status")
  void errorCodesAreUniqueAndWellFormed() {
    Set<String> codes = new HashSet<>();
    for (AuthErrorCode code : AuthErrorCode.values()) {
      assertThat(codes.add(code.code())).as("duplicate code %s", code).isTrue();
      assertThat(code.code()).isEqualTo(code.name());
      assertThat(code.status()).isBetween(400, 499);
      assertThat(code.title()).isNotBlank();
      assertThat(code.type()).startsWith("https://errors.acme-ecommerce.internal/");
      // The type URI must be derivable from the code, so a client can map either
      // way without a lookup table.
      assertThat(code.type())
          .endsWith(code.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
    }
  }

  @Test
  @DisplayName("the endpoint paths consumers build URLs from are fixed")
  void pathsAreStable() {
    assertThat(AuthClient.BASE_PATH).isEqualTo("/api/auth");
    // The gateway's JWKS configuration points at exactly this path.
    assertThat(AuthClient.JWKS_PATH).isEqualTo("/.well-known/jwks.json");
  }
}
