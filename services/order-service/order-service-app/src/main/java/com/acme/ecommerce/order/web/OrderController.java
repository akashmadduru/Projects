package com.acme.ecommerce.order.web;

import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.api.dto.OrderSummaryResponse;
import com.acme.ecommerce.order.api.dto.PlaceOrderRequest;
import com.acme.ecommerce.order.api.dto.PurchaseStatusResponse;
import com.acme.ecommerce.order.service.OrderService;
import com.acme.ecommerce.order.service.OrderService.Placement;
import com.acme.ecommerce.order.service.OrderStore;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The calling user own orders. The user id is ALWAYS the JWT subject. */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 50;
  private static final int MAX_KEY_LENGTH = 100;

  private final OrderService orders;
  private final OrderStore store;

  public OrderController(OrderService orders, OrderStore store) {
    this.orders = orders;
    this.store = store;
  }

  @PostMapping
  public ResponseEntity<OrderResponse> place(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody PlaceOrderRequest request) {
    if (idempotencyKey == null
        || idempotencyKey.isBlank()
        || idempotencyKey.length() > MAX_KEY_LENGTH) {
      throw ValidationException.forField(
          "Idempotency-Key", "is required and must be at most " + MAX_KEY_LENGTH + " characters");
    }
    Placement placement = orders.place(userId(jwt), request.addressId(), idempotencyKey.trim());
    return ResponseEntity.status(placement.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(placement.order());
  }

  @GetMapping
  public PagedModel<OrderSummaryResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
    PageRequest pageable =
        PageRequest.of(
            Math.max(page, 0),
            Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
            Sort.by(Sort.Direction.DESC, "placedAt"));
    return new PagedModel<>(store.list(userId(jwt), pageable));
  }

  /** Has the calling user bought this product? Used by rating-service to gate reviews. */
  @GetMapping("/purchases/{productId}")
  public PurchaseStatusResponse purchaseStatus(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
    return store.purchaseStatus(userId(jwt), productId);
  }

  @GetMapping("/{id}")
  public OrderResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return store.get(userId(jwt), id);
  }

  @PostMapping("/{id}/cancel")
  public OrderResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return store.cancel(userId(jwt), id);
  }

  private static UUID userId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
