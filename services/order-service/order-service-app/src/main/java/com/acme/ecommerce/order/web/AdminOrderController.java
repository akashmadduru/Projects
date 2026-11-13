package com.acme.ecommerce.order.web;

import com.acme.ecommerce.order.api.dto.AdminOrderStats;
import com.acme.ecommerce.order.api.dto.AdminOrderSummary;
import com.acme.ecommerce.order.api.dto.AdminRequests.CancelOrder;
import com.acme.ecommerce.order.api.dto.AdminRequests.ReturnDecision;
import com.acme.ecommerce.order.api.dto.AdminRequests.ReturnStatusUpdate;
import com.acme.ecommerce.order.api.dto.AdminRequests.StatusUpdate;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.api.dto.ReturnResponse;
import com.acme.ecommerce.order.domain.OrderStatus;
import com.acme.ecommerce.order.domain.ReturnStatus;
import com.acme.ecommerce.order.service.OrderLifecycleService;
import com.acme.ecommerce.order.service.OrderStore;
import com.acme.ecommerce.order.service.ReturnService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Back-office order and return management. Reads: ADMIN, SUPPORT, FINANCE. Writes: ADMIN (refund
 * pickup/refund steps also FINANCE). Enforced here, in the service.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAnyRole('ADMIN','SUPPORT','FINANCE')")
public class AdminOrderController {

  private static final int MAX_PAGE_SIZE = 50;

  private final OrderStore store;
  private final OrderLifecycleService lifecycle;
  private final ReturnService returns;

  public AdminOrderController(
      OrderStore store, OrderLifecycleService lifecycle, ReturnService returns) {
    this.store = store;
    this.lifecycle = lifecycle;
    this.returns = returns;
  }

  @GetMapping("/orders")
  public PagedModel<AdminOrderSummary> list(
      @RequestParam(required = false) OrderStatus status,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) UUID userId,
      @RequestParam(required = false) Boolean riskHold,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return new PagedModel<>(
        store.adminList(status, q, userId, riskHold, pageable(page, size, "placedAt")));
  }

  /** Releases a risk hold so fulfilment can proceed. */
  @PostMapping("/orders/{id}/release-hold")
  @PreAuthorize("hasRole('ADMIN')")
  public OrderResponse releaseHold(@PathVariable UUID id) {
    return store.releaseHold(id);
  }

  /** Dashboard counters. Declared before /orders/{id} only for readability; paths do not clash. */
  @GetMapping("/orders/stats")
  public AdminOrderStats stats() {
    return store.adminStats(Instant.now());
  }

  @GetMapping("/orders/{id}")
  public OrderResponse get(@PathVariable UUID id) {
    return store.getAny(id);
  }

  @PostMapping("/orders/{id}/status")
  @PreAuthorize("hasRole('ADMIN')")
  public OrderResponse updateStatus(
      @PathVariable UUID id, @Valid @RequestBody StatusUpdate request) {
    return lifecycle.adminAdvance(id, request);
  }

  /** Moves one shipment of a split order (works for single-shipment orders too). */
  @PostMapping("/orders/{id}/shipments/{shipmentId}/status")
  @PreAuthorize("hasRole('ADMIN')")
  public OrderResponse updateShipmentStatus(
      @PathVariable UUID id,
      @PathVariable UUID shipmentId,
      @Valid @RequestBody StatusUpdate request) {
    return lifecycle.adminAdvanceShipment(id, shipmentId, request);
  }

  @PostMapping("/orders/{id}/cancel")
  @PreAuthorize("hasRole('ADMIN')")
  public OrderResponse cancel(@PathVariable UUID id, @Valid @RequestBody CancelOrder request) {
    return lifecycle.cancelByAdmin(id, request.reason());
  }

  @GetMapping("/returns")
  public PagedModel<ReturnResponse> listReturns(
      @RequestParam(required = false) ReturnStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return new PagedModel<>(returns.adminList(status, pageable(page, size, "createdAt")));
  }

  @PostMapping("/returns/{id}/decision")
  @PreAuthorize("hasRole('ADMIN')")
  public ReturnResponse decide(@PathVariable UUID id, @Valid @RequestBody ReturnDecision request) {
    return returns.decide(id, request.approve(), request.note());
  }

  @PostMapping("/returns/{id}/status")
  @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
  public ReturnResponse returnStatus(
      @PathVariable UUID id, @Valid @RequestBody ReturnStatusUpdate request) {
    return switch (request.status()) {
      case PICKED_UP -> returns.pickedUp(id);
      case REFUNDED -> returns.refund(id);
    };
  }

  private static PageRequest pageable(int page, int size, String sortField) {
    return PageRequest.of(
        Math.max(page, 0),
        Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
        Sort.by(Sort.Direction.DESC, sortField));
  }
}
