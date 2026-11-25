package com.acme.ecommerce.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** TD-SEC-09: the prod profile refuses dev cookie and redirect settings. */
class ProductionSettingsGuardTest {

  private static AuthProperties bind(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bindOrCreate("auth", AuthProperties.class);
  }

  @Test
  void devDefaultsAreRejected() {
    AuthProperties dev =
        bind(
            Map.of(
                "auth.cookie.secure", "false",
                "auth.oauth2.allowed-redirect-uris", "http://localhost:5173/auth/callback"));

    assertThat(ProductionSettingsGuard.problems(dev))
        .anyMatch(p -> p.contains("auth.cookie.secure"))
        .anyMatch(p -> p.contains("http://localhost:5173"));
  }

  @Test
  void httpsPublicSettingsPass() {
    AuthProperties prod =
        bind(
            Map.of(
                "auth.cookie.secure", "true",
                "auth.oauth2.allowed-redirect-uris",
                    "https://shop.example.com/auth/callback,https://admin.example.com/auth/callback",
                "auth.oauth2.default-redirect-uri", "https://shop.example.com/auth/callback"));

    assertThat(ProductionSettingsGuard.problems(prod)).isEmpty();
  }
}
