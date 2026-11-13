package com.acme.ecommerce.auth.privacy;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 10 privacy: read-only JSON dump of everything this service holds about one user. Called
 * by user-service's export job (X-Internal-Token on /internal/**; never routed by the gateway).
 */
@RestController
public class PrivacyExportController {

  /** {section name, SQL with :u}. Each section becomes a JSON array of row objects. */
  private static final String[][] SECTIONS = {
    {"user", "SELECT id, email, email_verified, display_name, status, phone, phone_verified, created_at, updated_at FROM auth.users WHERE id = :u"},
    {"roles", "SELECT r.name, ur.granted_at FROM auth.user_roles ur JOIN auth.roles r ON r.id = ur.role_id WHERE ur.user_id = :u"},
    {"federatedIdentities", "SELECT provider, provider_email, linked_at, last_login_at FROM auth.federated_identities WHERE user_id = :u"},
    {"sessions", "SELECT family_id, auth_method, issued_at, expires_at, revoked_at, user_agent, host(ip_address) AS ip_address FROM auth.refresh_tokens WHERE user_id = :u ORDER BY issued_at DESC LIMIT 200"},
    {"twoFactor", "SELECT enabled, created_at, enabled_at FROM auth.user_totp WHERE user_id = :u"},
    {"knownDevices", "SELECT first_seen, last_seen FROM auth.known_devices WHERE user_id = :u"}
  };

  private final JdbcClient jdbc;

  public PrivacyExportController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping(value = "/internal/privacy/users/{userId}/export", produces = "application/json")
  public String export(@PathVariable UUID userId) {
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < SECTIONS.length; i++) {
      String json =
          jdbc.sql(
                  "SELECT coalesce(jsonb_agg(to_jsonb(t)), '[]'::jsonb)::text FROM ("
                      + SECTIONS[i][1]
                      + ") t")
              .param("u", userId)
              .query(String.class)
              .single();
      sb.append(i > 0 ? "," : "").append('"').append(SECTIONS[i][0]).append("\":").append(json);
    }
    return sb.append('}').toString();
  }
}
