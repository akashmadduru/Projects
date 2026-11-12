package com.acme.ecommerce.order.web;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.order.api.dto.AdminRequests.StatusUpdate;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.api.dto.SellerShipmentResponse;
import com.acme.ecommerce.order.domain.OrderStatus;
import com.acme.ecommerce.order.service.OrderLifecycleService;
import com.acme.ecommerce.order.service.OrderRemote;
import com.acme.ecommerce.order.service.OrderStore;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Marketplace sellers fulfilling their own shipments. The seller id is resolved from the JWT user
 * via seller-service (never taken from the request); a seller can only see/move their shipments.
 */
@RestController
@RequestMapping("/api/seller/shipments")
@PreAuthorize("hasAnyAuthority('SELLER', 'ROLE_SELLER')")
public class SellerShipmentController {

  private static final int MAX_PAGE_SIZE = 50;

  private final OrderStore store;
  private final OrderLifecycleService lifecycle;
  private final OrderRemote remote;

  public SellerShipmentController(
      OrderStore store, OrderLifecycleService lifecycle, OrderRemote remote) {
    this.store = store;
    this.lifecycle = lifecycle;
    this.remote = remote;
  }

  @GetMapping
  public PagedModel<SellerShipmentResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) OrderStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return new PagedModel<>(
        store.sellerShipments(
            sellerId(jwt),
            status,
            PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"))));
  }

  @GetMapping("/{id}")
  public SellerShipmentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return store.sellerShipment(sellerId(jwt), id);
  }

  @PostMapping("/{id}/status")
  public SellerShipmentResponse updateStatus(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody StatusUpdate request) {
    OrderResponse ignored = lifecycle.sellerAdvanceShipment(UUID.fromString(jwt.getSubject()), id, request);
    return store.sellerShipment(sellerId(jwt), id);
  }

  private UUID sellerId(Jwt jwt) {
    UUID sellerId = remote.approvedSellerId(UUID.fromString(jwt.getSubject()));
    if (sellerId == null) {
      throw new CodedException(ErrorCode.FORBIDDEN, "An approved seller account is required");
    }
    return sellerId;
  }
}
