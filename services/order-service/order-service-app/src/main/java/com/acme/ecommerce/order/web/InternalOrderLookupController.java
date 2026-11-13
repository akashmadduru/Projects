package com.acme.ecommerce.order.web;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.order.domain.Order;
import com.acme.ecommerce.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * support-service lookups (ownership check for tickets, chatbot "where is my order"). Never routed
 * by the gateway; guarded by X-Internal-Token like the rest of /internal/**.
 */
@RestController
@RequestMapping("/internal/orders")
public class InternalOrderLookupController {

  public record OrderBrief(
      UUID id,
      UUID userId,
      String status,
      String paymentStatus,
      String currency,
      BigDecimal totalAmount,
      Instant placedAt,
      String carrier,
      String trackingNumber,
      LocalDate estimatedDeliveryDate) {}

  private final OrderRepository orders;

  public InternalOrderLookupController(OrderRepository orders) {
    this.orders = orders;
  }

  /** The user's most recent orders, newest first (limit 1..20). */
  @GetMapping("/by-user/{userId}")
  public List<OrderBrief> byUser(
      @PathVariable UUID userId, @RequestParam(defaultValue = "5") int limit) {
    int n = Math.max(1, Math.min(limit, 20));
    return orders
        .findAll(
            (root, query, cb) -> cb.equal(root.get("userId"), userId),
            PageRequest.of(0, n, Sort.by(Sort.Direction.DESC, "placedAt")))
        .stream()
        .map(InternalOrderLookupController::brief)
        .toList();
  }

  /** One order's brief, including the owner (caller compares with the ticket author). */
  @GetMapping("/{id}/brief")
  public OrderBrief one(@PathVariable UUID id) {
    return orders
        .findById(id)
        .map(InternalOrderLookupController::brief)
        .orElseThrow(() -> new ResourceNotFoundException("Order", id));
  }

  private static OrderBrief brief(Order o) {
    return new OrderBrief(
        o.getId(),
        o.getUserId(),
        o.getStatus().name(),
        o.getPaymentStatus() == null ? null : o.getPaymentStatus().name(),
        o.getCurrency(),
        o.getTotalAmount(),
        o.getPlacedAt(),
        o.getCarrier(),
        o.getTrackingNumber(),
        o.getEstimatedDeliveryDate());
  }
}
