package com.acme.ecommerce.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ecommerce.auth.config.AuthProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The open-redirect defence. */
class RedirectTargetPolicyTest {

  private static final String ALLOWED = "https://admin.example.com/auth/callback";
  private static final String ALSO_ALLOWED = "http://localhost:5173/auth/callback";

  private static RedirectTargetPolicy policy() {
    return new RedirectTargetPolicy(propertiesWith(List.of(ALLOWED, ALSO_ALLOWED), ALLOWED));
  }

  @Test
  @DisplayName("an allowlisted target is returned unchanged")
  void allowsListedTarget() {
    assertThat(policy().resolve(ALSO_ALLOWED)).isEqualTo(ALSO_ALLOWED);
  }

  @Test
  @DisplayName("a hostile target falls back to the default instead of being honoured")
  void rejectsHostileTargets() {
    RedirectTargetPolicy policy = policy();

    // The classic bypasses a prefix or suffix match would let through.
    assertThat(policy.resolve("https://evil.example/steal")).isEqualTo(ALLOWED);
    assertThat(policy.resolve("https://admin.example.com.evil.test/auth/callback"))
        .isEqualTo(ALLOWED);
    assertThat(policy.resolve("https://evil-admin.example.com/auth/callback")).isEqualTo(ALLOWED);
    assertThat(policy.resolve("https://admin.example.com/auth/callback/../../evil"))
        .isEqualTo(ALLOWED);
    assertThat(policy.resolve("javascript:alert(1)")).isEqualTo(ALLOWED);
    assertThat(policy.resolve("//evil.example")).isEqualTo(ALLOWED);
  }

  @Test
  @DisplayName("missing or blank input yields the default")
  void defaultsWhenAbsent() {
    assertThat(policy().resolve(null)).isEqualTo(ALLOWED);
    assertThat(policy().resolve("   ")).isEqualTo(ALLOWED);
  }

  @Test
  @DisplayName("comparison is case- and default-port-insensitive, so trivial variants still match")
  void normalisesBeforeComparing() {
    assertThat(policy().resolve("HTTPS://Admin.Example.COM:443/auth/callback")).isEqualTo(ALLOWED);
  }

  @Test
  @DisplayName("a default target that is not itself allowlisted fails fast at startup")
  void defaultMustBeAllowlisted() {
    assertThatThrownBy(
            () ->
                new RedirectTargetPolicy(
                    propertiesWith(List.of(ALLOWED), "https://other.example/cb")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must also appear in");
  }

  @Test
  @DisplayName("CORS origins are derived from the same allowlist")
  void derivesCorsOrigins() {
    assertThat(policy().allowedOrigins())
        .containsExactlyInAnyOrder("https://admin.example.com", "http://localhost:5173");
  }

  private static AuthProperties propertiesWith(List<String> allowed, String defaultTarget) {
    return new AuthProperties(
        new AuthProperties.Jwt("iss", List.of("aud"), Duration.ofMinutes(10), "path", "pw", ""),
        new AuthProperties.Refresh(
            Duration.ofDays(14), Duration.ofDays(30), Duration.ofSeconds(10)),
        new AuthProperties.Cookie("refresh_token", "/api/auth", true, "Lax", ""),
        new AuthProperties.Registration(false),
        new AuthProperties.Lockout(5, Duration.ofMinutes(1), Duration.ofMinutes(15)),
        new AuthProperties.RateLimit(true, 10, 60, Duration.ofMinutes(1), false, 1000),
        new AuthProperties.Oauth2("", "", allowed, defaultTarget, ""),
        new AuthProperties.Housekeeping(false, Duration.ofDays(30), Duration.ofHours(1)));
  }
}
