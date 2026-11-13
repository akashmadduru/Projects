package com.acme.ecommerce.order.privacy;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Phase 10 privacy: what stops an account deletion (asked by user-service, internal token). */
@RestController
public class PrivacyBlockersController {

  private final JdbcClient jdbc;

  public PrivacyBlockersController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/internal/privacy/users/{userId}/deletion-blockers")
  public Map<String, Integer> blockers(@PathVariable UUID userId) {
    int orders =
        jdbc.sql(
                "SELECT count(*) FROM orders.orders WHERE user_id = :u AND status NOT IN"
                    + " ('DELIVERED','CANCELLED')")
            .param("u", userId)
            .query(Integer.class)
            .single();
    int returns =
        jdbc.sql(
                "SELECT count(*) FROM orders.order_returns WHERE user_id = :u AND status IN"
                    + " ('REQUESTED','APPROVED','PICKED_UP')")
            .param("u", userId)
            .query(Integer.class)
            .single();
    return Map.of("openOrders", orders, "openReturns", returns);
  }
}
