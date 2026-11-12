package com.acme.ecommerce.inventory.web;

import com.acme.ecommerce.inventory.api.InventoryClient;
import com.acme.ecommerce.inventory.api.dto.InventoryResponse;
import com.acme.ecommerce.inventory.service.InventoryService;
import com.acme.ecommerce.inventory.web.dto.CreateInventoryRequest;
import com.acme.ecommerce.inventory.web.dto.UpdateInventoryRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP edge of inventory-service.
 *
 * <p>Deliberately thin: bind, delegate, return. No branching, no persistence, no error handling -
 * {@code ResourceNotFoundException}/{@code ResourceConflictException} propagate to common-web's
 * {@code GlobalExceptionHandler}, which is the single place in the platform an exception becomes a
 * response body.
 *
 * <p>{@code @PreAuthorize} ON WRITES ONLY, per the confirmed RBAC decision (HLD S9): {@code
 * CATALOG_MANAGER}/{@code ADMIN} on {@code create}/{@code update}/{@code delete}, reusing the same
 * gate as product writes rather than a new role. Reads carry no annotation - {@code
 * anyRequest().authenticated()} in {@code SecurityConfig} already covers them, matching
 * product-service's posture that a rule should live in exactly one place unless it genuinely
 * differs by method, which is exactly why the writes below need one and the reads do not.
 *
 * <p>THE {@code X-User-*} HEADERS INJECTED BY THE GATEWAY ARE NOT READ HERE. The caller's identity,
 * when this service needs it, comes from the validated token via {@code @AuthenticationPrincipal}.
 */
@RestController
@RequestMapping(InventoryClient.BASE_PATH)
public class InventoryController {

  /**
   * Page size when the caller does not ask for one.
   *
   * <p>20 rows is a screenful and keeps the response comfortably inside the gateway's 1s {@code
   * inventoryCb} slow-call threshold. {@code spring.data.web.pageable.max-page-size} caps what a
   * caller may request, so {@code ?size=100000} cannot turn a paged endpoint into a full-table
   * export.
   */
  private static final int DEFAULT_PAGE_SIZE = 20;

  private final InventoryService inventory;

  public InventoryController(InventoryService inventory) {
    this.inventory = inventory;
  }

  /**
   * One page of stock levels.
   *
   * <p>DEFAULT SORT IS {@code productId}, not "whatever the database returns" - {@code
   * inventory.stock_levels.product_id} is UNIQUE, so the ordering is total and a client walking
   * pages sees a deterministic, stable boundary across concurrent writes (mirrors product-service's
   * {@code sku ASC} default for the same reason).
   *
   * @param pageable page/size/sort, bound from the query string
   * @return the requested page of stock levels
   */
  @GetMapping
  public PagedModel<InventoryResponse> list(
      @PageableDefault(size = DEFAULT_PAGE_SIZE, sort = "productId", direction = Sort.Direction.ASC)
          Pageable pageable) {
    return new PagedModel<>(inventory.list(pageable));
  }

  /**
   * One stock-level row by id.
   *
   * @param id the inventory row identifier
   * @return the row
   */
  @GetMapping("/{id}")
  public InventoryResponse findById(@PathVariable UUID id) {
    return inventory.findById(id);
  }

  /**
   * The stock-level row for a product, if one has been provisioned.
   *
   * <p>Backs {@code docs/HLD-catalog-enrichment-and-saga.md} S7.3's narrowly-approved read-only
   * cross-service call - product-service's {@code CatalogAggregateService} is the one consumer of
   * this endpoint, calling it through its own {@code aggregateCb} circuit breaker (docs/adr/0003,
   * Decision 2). Like every other read here, no {@code @PreAuthorize}: {@code
   * anyRequest().authenticated()} in {@code SecurityConfig} already covers it.
   *
   * @param productId the product to look up stock for
   * @return the matching row
   */
  public record VariantStockRequest(@jakarta.validation.constraints.NotNull UUID productId, @jakarta.validation.constraints.Min(0) int quantity) {}

  @GetMapping("/variants")
  public java.util.List<InventoryResponse.VariantQuantity> variants(
      @org.springframework.web.bind.annotation.RequestParam java.util.List<UUID> ids) {
    return inventory.variantQuantities(ids);
  }

  @PutMapping("/variants/{variantId}")
  @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
  public void setVariantStock(
      @PathVariable UUID variantId, @Valid @RequestBody VariantStockRequest request) {
    inventory.setVariantStock(variantId, request.productId(), request.quantity());
  }

  @GetMapping("/by-product/{productId}")
  public InventoryResponse findByProductId(@PathVariable UUID productId) {
    return inventory.findByProductId(productId);
  }

  /**
   * Provisions a stock-level row directly.
   *
   * @param request the product to provision and its starting quantity
   * @return the created row
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
  public InventoryResponse create(@Valid @RequestBody CreateInventoryRequest request) {
    return inventory.create(request);
  }

  /**
   * Updates the quantity on an existing row.
   *
   * @param id the inventory row identifier
   * @param request the new quantity and the version the caller last read
   * @return the updated row
   */
  @PutMapping("/{id}")
  @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
  public InventoryResponse update(
      @PathVariable UUID id, @Valid @RequestBody UpdateInventoryRequest request) {
    return inventory.update(id, request);
  }

  /**
   * Deletes a stock-level row.
   *
   * @param id the inventory row identifier
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
  public void delete(@PathVariable UUID id) {
    inventory.delete(id);
  }
}
