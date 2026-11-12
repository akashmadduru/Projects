package com.acme.ecommerce.order.web;

import com.acme.ecommerce.common.core.error.ResourceConflictException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.order.api.dto.CreateReturnRequest;
import com.acme.ecommerce.order.api.dto.DeliveryEstimateResponse;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.api.dto.OrderSummaryResponse;
import com.acme.ecommerce.order.api.dto.PlaceOrderRequest;
import com.acme.ecommerce.order.api.dto.PurchaseStatusResponse;
import com.acme.ecommerce.order.api.dto.ReturnResponse;
import com.acme.ecommerce.order.service.InvoiceService;
import com.acme.ecommerce.order.service.OrderLifecycleService;
import com.acme.ecommerce.order.service.OrderService;
import com.acme.ecommerce.order.service.OrderService.Placement;
import com.acme.ecommerce.order.service.OrderStore;
import com.acme.ecommerce.order.service.PricingService;
import com.acme.ecommerce.order.service.ReturnService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
  private final OrderLifecycleService lifecycle;
  private final ReturnService returns;
  private final InvoiceService invoices;
  private final PricingService pricing;

  public OrderController(
      OrderService orders,
      OrderStore store,
      OrderLifecycleService lifecycle,
      ReturnService returns,
      InvoiceService invoices,
      PricingService pricing) {
    this.orders = orders;
    this.store = store;
    this.lifecycle = lifecycle;
    this.returns = returns;
    this.invoices = invoices;
    this.pricing = pricing;
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
    Placement placement =
        orders.place(userId(jwt), jwt.getClaimAsString("email"), idempotencyKey.trim(), request);
    return ResponseEntity.status(placement.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(placement.order());
  }

  /** PUBLIC (gateway + service): delivery date, shipping fee and COD availability for a pincode. */
  @GetMapping("/delivery-estimate")
  public DeliveryEstimateResponse deliveryEstimate(
      @RequestParam String pincode,
      @RequestParam(required = false) BigDecimal subtotal,
      @RequestParam(required = false) String currency) {
    return pricing.estimate(pincode, subtotal, currency);
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
    return lifecycle.cancelByUser(userId(jwt), id);
  }

  @GetMapping(value = "/{id}/invoice.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> invoice(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    OrderResponse order = store.get(userId(jwt), id);
    if ("PENDING_PAYMENT".equals(order.status()) || "CANCELLED".equals(order.status())) {
      throw new ResourceConflictException("No invoice is available for this order yet");
    }
    byte[] pdf = invoices.render(order);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.inline()
                .filename(InvoiceService.invoiceNumber(order) + ".pdf")
                .build()
                .toString())
        .body(pdf);
  }

  @PostMapping("/{id}/returns")
  public ResponseEntity<ReturnResponse> requestReturn(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody CreateReturnRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(returns.request(userId(jwt), id, request));
  }

  @GetMapping("/{id}/returns")
  public List<ReturnResponse> listReturns(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return returns.list(userId(jwt), id);
  }

  private static UUID userId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
