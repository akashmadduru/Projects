package com.acme.ecommerce.payment.privacy;

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
    {"payments", "SELECT * FROM payment.payments WHERE user_id = :u ORDER BY created_at DESC"},
    {"refunds", "SELECT r.* FROM payment.refunds r JOIN payment.payments p ON p.id = r.payment_id WHERE p.user_id = :u"},
    {"wallet", "SELECT * FROM payment.wallets WHERE user_id = :u"},
    {"walletTransactions", "SELECT * FROM payment.wallet_txns WHERE user_id = :u ORDER BY created_at DESC"},
    {"giftCards", "SELECT id, amount, currency, status, purchaser_email, recipient_email, message, redeemed_at, created_at, expires_at FROM payment.gift_cards WHERE purchaser_user_id = :u OR redeemed_by = :u"}
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
