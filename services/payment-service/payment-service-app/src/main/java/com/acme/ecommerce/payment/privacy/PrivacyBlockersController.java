package com.acme.ecommerce.payment.privacy;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Phase 10 privacy: in-flight payments block account deletion (asked by user-service). */
@RestController
public class PrivacyBlockersController {

  private final JdbcClient jdbc;

  public PrivacyBlockersController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/internal/privacy/users/{userId}/deletion-blockers")
  public Map<String, Integer> blockers(@PathVariable UUID userId) {
    int pending =
        jdbc.sql(
                "SELECT count(*) FROM payment.payments WHERE user_id = :u AND status = 'PROCESSING'")
            .param("u", userId)
            .query(Integer.class)
            .single();
    return Map.of("pendingPayments", pending);
  }
}
