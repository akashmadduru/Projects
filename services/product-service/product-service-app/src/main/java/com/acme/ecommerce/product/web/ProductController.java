package com.acme.ecommerce.product.web;

import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.product.api.ProductClient;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import com.acme.ecommerce.product.repository.ProductFilter;
import com.acme.ecommerce.product.service.CatalogAggregateService;
import com.acme.ecommerce.product.service.ProductCatalogService;
import com.acme.ecommerce.product.web.dto.CreateProductRequest;
import com.acme.ecommerce.product.web.dto.ProductCatalogAggregateResponse;
import com.acme.ecommerce.product.web.dto.UpdateProductRequest;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP edge of product-service.
 *
 * <p>Deliberately thin: bind, delegate, return. No branching, no persistence, no error handling -
 * {@code ResourceNotFoundException}/{@code ResourceConflictException} propagate to common-web's
 * {@code GlobalExceptionHandler}, which is the single place in the platform an exception becomes a
 * response body.
 *
 * <p>{@code @PreAuthorize} ON WRITES ONLY, per the confirmed RBAC decision (HLD S9): {@code
 * CATALOG_MANAGER}/{@code ADMIN} on {@code create}/{@code update}/{@code delete}. Reads carry no
 * annotation - {@code anyRequest().authenticated()} in {@code SecurityConfig} already covers them.
 * This is a deliberate departure from the "one place expresses one rule" posture that applied when
 * this controller had only reads: role differs by HTTP method on the same resource, which a single
 * {@code authorizeHttpRequests} matcher cannot express as cleanly as method-level annotations
 * without duplicating the path.
 *
 * <p>THE {@code X-User-*} HEADERS INJECTED BY THE GATEWAY ARE NOT READ HERE. The caller's identity,
 * when this service needs it, comes from the validated token via {@code @AuthenticationPrincipal}.
 */
@RestController
@RequestMapping(ProductClient.BASE_PATH)
@RequiredArgsConstructor
public class ProductController {

  /**
   * Page size when the caller does not ask for one.
   *
   * <p>20 rows is a screenful and keeps the response comfortably inside the gateway's 1s {@code
   * productCb} slow-call threshold. {@code spring.data.web.pageable.max-page-size} caps what a
   * caller may request, so {@code ?size=100000} cannot turn a paged endpoint into a full-table
   * export.
   */
  private static final int DEFAULT_PAGE_SIZE = 20;

  /**
   * Properties a caller may sort by. Anything else would surface as Spring Data's {@code
   * PropertyReferenceException}, which the shared advice can only treat as a 500 - and would also
   * let a caller sort on a column nobody indexed.
   */
  private static final Set<String> SORTABLE =
      Set.of("sku", "name", "priceAmount", "averageRating", "discountPercent", "createdAt");

  private final ProductCatalogService catalog;
  private final CatalogAggregateService catalogAggregateService;

  /**
   * One page of the catalog.
   *
   * <p>DEFAULT SORT IS {@code sku}, not "whatever the database returns". An unordered paged query
   * has no stable page boundaries - Postgres is free to return rows in a different order between
   * two calls, so a client walking pages can see a row twice and never see another. {@code sku} is
   * UNIQUE, so the ordering is total and the walk is deterministic.
   *
   * <p>Returns {@link PagedModel} rather than the raw {@code Page}. Serialising {@code PageImpl}
   * directly emits Spring Data's internal shape, which the framework itself warns is not a stable
   * contract; {@code PagedModel} is the documented, stable {@code {content, page}} envelope.
   *
   * <p>Every filter is optional and they combine with AND. Range bounds are inclusive.
   *
   * @param categoryId a category; a root category also matches its subcategories' products
   * @param q case-insensitive substring of the name or sku
   * @param minPrice lower price bound
   * @param maxPrice upper price bound
   * @param minRating lowest average rating (0-5); unrated products are excluded
   * @param minDiscount lowest discount percentage (0-100)
   * @param pageable page/size/sort, bound from the query string; sort limited to {@link #SORTABLE}
   * @return the requested page of products
   */
  @GetMapping
  public PagedModel<ProductResponse> list(
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) BigDecimal minPrice,
      @RequestParam(required = false) BigDecimal maxPrice,
      @RequestParam(required = false) BigDecimal minRating,
      @RequestParam(required = false) BigDecimal minDiscount,
      @PageableDefault(size = DEFAULT_PAGE_SIZE, sort = "sku", direction = Sort.Direction.ASC)
          Pageable pageable) {
    requireInRange("minPrice", minPrice, BigDecimal.ZERO, null);
    requireInRange("maxPrice", maxPrice, BigDecimal.ZERO, null);
    if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
      throw ValidationException.forField("minPrice", "must not be greater than maxPrice");
    }
    requireInRange("minRating", minRating, BigDecimal.ZERO, BigDecimal.valueOf(5));
    requireInRange("minDiscount", minDiscount, BigDecimal.ZERO, BigDecimal.valueOf(100));
    for (Sort.Order order : pageable.getSort()) {
      if (!SORTABLE.contains(order.getProperty())) {
        throw ValidationException.forField(
            "sort", "unsupported property '" + order.getProperty() + "'; use one of " + SORTABLE);
      }
    }

    ProductFilter filter =
        new ProductFilter(categoryId, q, minPrice, maxPrice, minRating, minDiscount);
    return new PagedModel<>(catalog.list(filter, pageable));
  }

  private static void requireInRange(
      String field, BigDecimal value, BigDecimal min, BigDecimal max) {
    if (value == null) {
      return;
    }
    if (value.compareTo(min) < 0 || (max != null && value.compareTo(max) > 0)) {
      throw ValidationException.forField(
          field,
          max == null ? "must be at least " + min : "must be between " + min + " and " + max);
    }
  }

  /**
   * One product by id.
   *
   * <p>404 as an RFC 9457 problem document when the id is unknown; 400, likewise, when it is not a
   * UUID at all (MVC's type-mismatch, translated by the shared advice).
   *
   * @param id the product identifier
   * @return the product
   */
  @GetMapping("/{id}")
  public ProductResponse findById(@PathVariable UUID id) {
    return catalog.findById(id);
  }

  /**
   * A product composed with its stock level, in one response.
   *
   * <p>THE ONE, NARROWLY-APPROVED READ-ONLY SYNCHRONOUS CALL FROM THIS SERVICE TO inventory-service
   * (docs/HLD-catalog-enrichment-and-saga.md S7.3, docs/adr/0003 Decision 2) - everything else this
   * controller does stays entirely within this service's own database and events. {@code
   * /catalog/products/{id}}, nested under this controller's existing {@code /api/products} mapping
   * rather than a new top-level path, so the gateway's existing {@code Path=/api/products/**} route
   * already covers it - no gateway route change, matching the HLD's Major Components table
   * ("gateway: No route changes").
   *
   * <p>404 as an RFC 9457 problem document when the product itself is unknown, exactly like {@link
   * #findById}. Inventory being unreachable, slow, or not-yet-provisioned is NEVER a failure of
   * this endpoint - see {@code CatalogAggregateService}, whose response always has a valid, if
   * possibly {@code null}, {@code inventory} member.
   *
   * <p>No {@code @PreAuthorize}: authenticated (any role), same as every other read here - it
   * composes two already-authenticated-any-role reads and introduces no new authorization surface
   * (HLD S9).
   *
   * @param id the product identifier
   * @return the product, plus its stock level if inventory-service could answer for it
   */
  @GetMapping("/catalog/products/{id}")
  public ProductCatalogAggregateResponse getAggregate(@PathVariable UUID id) {
    return catalogAggregateService.getAggregate(id);
  }

  /**
   * Creates a product.
   *
   * @param request the product to create
   * @return the created product
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
  public ProductResponse create(@Valid @RequestBody CreateProductRequest request) {
    return catalog.create(request);
  }

  /**
   * Updates an existing product.
   *
   * @param id the product identifier
   * @param request the new field values and the version the caller last read
   * @return the updated product
   */
  @PutMapping("/{id}")
  @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
  public ProductResponse update(
      @PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
    return catalog.update(id, request);
  }

  /**
   * Deletes a product.
   *
   * @param id the product identifier
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
  public void delete(@PathVariable UUID id) {
    catalog.delete(id);
  }
}
