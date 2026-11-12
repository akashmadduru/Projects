package com.acme.ecommerce.order.service;

import com.acme.ecommerce.common.core.error.DomainRuleViolationException;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.order.api.dto.OrderItemResponse;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.api.dto.OrderSummaryResponse;
import com.acme.ecommerce.order.api.dto.PurchaseStatusResponse;
import com.acme.ecommerce.order.api.dto.ShipToResponse;
import com.acme.ecommerce.order.domain.Order;
import com.acme.ecommerce.order.domain.OrderStatus;
import com.acme.ecommerce.order.repository.OrderRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional persistence boundary for orders. Kept separate from {@link OrderService} so that
 * the orchestration there (which makes remote calls) never holds a database transaction open, and
 * so a unique-constraint race on the idempotency key surfaces OUTSIDE the failed transaction.
 */
@Service
public class OrderStore {

  private final OrderRepository orders;

  public OrderStore(OrderRepository orders) {
    this.orders = orders;
  }

  /** The order previously placed with this key, if any; a different address is a 422. */
  @Transactional(readOnly = true)
  public Optional<OrderResponse> findReplay(UUID userId, String key, UUID addressId) {
    return orders
        .findByUserIdAndIdempotencyKey(userId, key)
        .map(
            existing -> {
              if (!existing.getAddressId().equals(addressId)) {
                throw new DomainRuleViolationException(
                    "Idempotency-Key was already used with a different request",
                    "IDEMPOTENCY_KEY_REUSED");
              }
              return toResponse(existing);
            });
  }

  @Transactional
  public OrderResponse save(Order order) {
    return toResponse(orders.saveAndFlush(order));
  }

  @Transactional(readOnly = true)
  public OrderResponse get(UUID userId, UUID id) {
    return toResponse(find(userId, id));
  }

  @Transactional(readOnly = true)
  public Page<OrderSummaryResponse> list(UUID userId, Pageable pageable) {
    return orders.findSummaries(userId, pageable);
  }

  /** Whether the user has a non-cancelled order containing the product. */
  @Transactional(readOnly = true)
  public PurchaseStatusResponse purchaseStatus(UUID userId, UUID productId) {
    return orders
        .findContainingProduct(userId, productId, OrderStatus.CANCELLED, PageRequest.of(0, 1))
        .stream()
        .findFirst()
        .map(o -> new PurchaseStatusResponse(productId, true, o.getId(), o.getPlacedAt()))
        .orElseGet(() -> new PurchaseStatusResponse(productId, false, null, null));
  }

  @Transactional
  public OrderResponse cancel(UUID userId, UUID id) {
    Order order = find(userId, id);
    order.cancel(Instant.now());
    return toResponse(order);
  }

  private Order find(UUID userId, UUID id) {
    return orders
        .findByIdAndUserId(id, userId)
        .orElseThrow(() -> new ResourceNotFoundException("Order", id));
  }

  private static OrderResponse toResponse(Order o) {
    return new OrderResponse(
        o.getId(),
        o.getStatus().name(),
        o.getCurrency(),
        o.getTotalAmount(),
        o.getPlacedAt(),
        o.getCancelledAt(),
        new ShipToResponse(
            o.getShipFullName(),
            o.getShipPhone(),
            o.getShipLine1(),
            o.getShipLine2(),
            o.getShipCity(),
            o.getShipState(),
            o.getShipPostalCode(),
            o.getShipCountry()),
        o.getItems().stream()
            .map(
                i ->
                    new OrderItemResponse(
                        i.getProductId(),
                        i.getSku(),
                        i.getName(),
                        i.getUnitPrice(),
                        i.getQuantity(),
                        i.getLineTotal(),
                        i.getImageUrl()))
            .toList());
  }
}
