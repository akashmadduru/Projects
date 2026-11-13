package com.acme.ecommerce.order.privacy;

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
    {"orders", "SELECT to_jsonb(o) - 'risk_score' - 'risk_reasons' - 'risk_hold' - 'idempotency_key' AS o FROM orders.orders o WHERE o.user_id = :u ORDER BY o.placed_at DESC"},
    {"orderItems", "SELECT i.* FROM orders.order_items i JOIN orders.orders o ON o.id = i.order_id WHERE o.user_id = :u"},
    {"orderStatusHistory", "SELECT h.* FROM orders.order_status_history h JOIN orders.orders o ON o.id = h.order_id WHERE o.user_id = :u"},
    {"shipments", "SELECT s.* FROM orders.order_shipments s JOIN orders.orders o ON o.id = s.order_id WHERE o.user_id = :u"},
    {"returns", "SELECT * FROM orders.order_returns WHERE user_id = :u"},
    {"returnItems", "SELECT ri.* FROM orders.order_return_items ri JOIN orders.order_returns r ON r.id = ri.return_id WHERE r.user_id = :u"},
    {"subscriptions", "SELECT * FROM orders.subscriptions WHERE user_id = :u"}
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
