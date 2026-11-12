package com.acme.ecommerce.order.web;

import com.acme.ecommerce.order.repository.OrderRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** PUBLIC co-purchase recommendations from non-cancelled orders. */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

  public record Scored(UUID productId, long score) {}

  private final OrderRepository orders;

  public RecommendationController(OrderRepository orders) {
    this.orders = orders;
  }

  @GetMapping("/products/{id}/bought-together")
  @Transactional(readOnly = true)
  public List<Scored> boughtTogether(
      @PathVariable UUID id, @RequestParam(defaultValue = "4") int limit) {
    int lim = Math.max(1, Math.min(limit, 20));
    return orders.boughtTogether(id, lim).stream()
        .map(r -> new Scored((UUID) r[0], ((Number) r[1]).longValue()))
        .toList();
  }
}
