package com.acme.ecommerce.order.web;

import com.acme.ecommerce.order.repository.OrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Distinct products from the caller's delivered orders, newest first. */
@RestController
public class BuyAgainController {

  public record BuyAgainItem(UUID productId, UUID variantId, Instant lastOrderedAt, long timesOrdered) {}

  private final OrderRepository orders;

  public BuyAgainController(OrderRepository orders) {
    this.orders = orders;
  }

  @GetMapping("/api/orders/buy-again")
  @Transactional(readOnly = true)
  public List<BuyAgainItem> buyAgain(
      @AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "20") int limit) {
    int lim = Math.max(1, Math.min(limit, 100));
    return orders.buyAgain(UUID.fromString(jwt.getSubject()), lim).stream()
        .map(
            r ->
                new BuyAgainItem(
                    (UUID) r[0], (UUID) r[1], Time.instant(r[2]), ((Number) r[3]).longValue()))
        .toList();
  }
}
