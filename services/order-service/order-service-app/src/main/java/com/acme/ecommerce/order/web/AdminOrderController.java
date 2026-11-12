package com.acme.ecommerce.order.web;

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

/** Back-office order and return management. ADMIN only (enforced here, in the service). */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAnyAuthority('ADMIN', 'ROLE_ADMIN')")
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
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return new PagedModel<>(store.adminList(status, q, pageable(page, size, "placedAt")));
  }

  @GetMapping("/orders/{id}")
  public OrderResponse get(@PathVariable UUID id) {
    return store.getAny(id);
  }

  @PostMapping("/orders/{id}/status")
  public OrderResponse updateStatus(
      @PathVariable UUID id, @Valid @RequestBody StatusUpdate request) {
    return lifecycle.adminAdvance(id, request);
  }

  @PostMapping("/orders/{id}/cancel")
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
  public ReturnResponse decide(@PathVariable UUID id, @Valid @RequestBody ReturnDecision request) {
    return returns.decide(id, request.approve(), request.note());
  }

  @PostMapping("/returns/{id}/status")
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
