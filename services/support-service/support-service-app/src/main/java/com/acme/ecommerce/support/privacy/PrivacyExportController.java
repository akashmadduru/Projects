package com.acme.ecommerce.support.privacy;

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
    {"tickets", "SELECT * FROM support.tickets WHERE user_id = :u ORDER BY created_at DESC"},
    {"ticketMessages", "SELECT m.* FROM support.ticket_messages m JOIN support.tickets t ON t.id = m.ticket_id WHERE t.user_id = :u AND NOT m.internal ORDER BY m.created_at"},
    {"chatSessions", "SELECT * FROM support.chat_sessions WHERE user_id = :u"},
    {"chatMessages", "SELECT m.* FROM support.chat_messages m JOIN support.chat_sessions s ON s.session_id = m.session_id WHERE s.user_id = :u ORDER BY m.created_at"}
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
