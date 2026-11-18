package com.acme.ecommerce.auth.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start under the {@code prod} profile with development cookie or redirect settings
 * (TD-SEC-09): a refresh cookie without {@code Secure}, or a post-login redirect allow-list that
 * contains plain-HTTP or localhost URLs, both of which the dev defaults have.
 */
@Component
@Profile("prod")
class ProductionSettingsGuard {

  ProductionSettingsGuard(AuthProperties properties) {
    List<String> problems = problems(properties);
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "Unsafe auth settings for the prod profile: " + String.join("; ", problems));
    }
  }

  static List<String> problems(AuthProperties properties) {
    List<String> problems = new ArrayList<>();
    if (!properties.cookie().secure()) {
      problems.add("auth.cookie.secure must be true (AUTH_COOKIE_SECURE)");
    }
    List<String> uris = new ArrayList<>(properties.oauth2().allowedRedirectUris());
    uris.add(properties.oauth2().defaultRedirectUri());
    for (String uri : uris) {
      if (uri == null || uri.isBlank()) {
        continue;
      }
      if (!isPublicHttps(uri.trim())) {
        problems.add(
            "redirect URI must be https and not localhost: "
                + uri
                + " (AUTH_ALLOWED_REDIRECT_URIS / AUTH_DEFAULT_REDIRECT_URI)");
      }
    }
    return problems;
  }

  private static boolean isPublicHttps(String uri) {
    try {
      URI u = URI.create(uri);
      String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
      return "https".equalsIgnoreCase(u.getScheme())
          && !host.isEmpty()
          && !host.equals("localhost")
          && !host.endsWith(".localhost")
          && !host.equals("127.0.0.1")
          && !host.equals("[::1]");
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
