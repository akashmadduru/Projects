package com.acme.ecommerce.cart.privacy;

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
    {"cart", "SELECT * FROM cart.carts WHERE user_id = :u"},
    {"cartItems", "SELECT i.* FROM cart.cart_items i JOIN cart.carts c ON c.id = i.cart_id WHERE c.user_id = :u"},
    {"wishlist", "SELECT * FROM cart.wishlist_items WHERE user_id = :u"},
    {"savedForLater", "SELECT * FROM cart.saved_items WHERE user_id = :u"},
    {"alerts", "SELECT * FROM cart.alerts WHERE user_id = :u"},
    {"lists", "SELECT * FROM cart.lists WHERE user_id = :u"},
    {"listItems", "SELECT i.* FROM cart.list_items i JOIN cart.lists l ON l.id = i.list_id WHERE l.user_id = :u"}
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
