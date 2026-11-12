package com.acme.ecommerce.product.service;

import com.acme.ecommerce.common.core.error.ResourceConflictException;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.product.api.dto.ProductImageResponse;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import com.acme.ecommerce.product.api.dto.ProductVariantResponse;
import com.acme.ecommerce.product.client.InventoryDto;
import com.acme.ecommerce.product.client.InventoryServiceClient;
import com.acme.ecommerce.product.client.RatingSummary;
import com.acme.ecommerce.product.domain.Offer;
import com.acme.ecommerce.product.domain.Product;
import com.acme.ecommerce.product.domain.ProductImage;
import com.acme.ecommerce.product.domain.ProductVariant;
import com.acme.ecommerce.product.event.ProductEventPublisher;
import com.acme.ecommerce.product.event.ProductEventType;
import com.acme.ecommerce.product.repository.ProductFilter;
import com.acme.ecommerce.product.repository.ProductImageRepository;
import com.acme.ecommerce.product.repository.ProductNativeQueries;
import com.acme.ecommerce.product.repository.ProductRepository;
import com.acme.ecommerce.product.repository.ProductSpecifications;
import com.acme.ecommerce.product.repository.ProductVariantRepository;
import com.acme.ecommerce.product.web.dto.CreateProductRequest;
import com.acme.ecommerce.product.web.dto.ProductImageRequest;
import com.acme.ecommerce.product.web.dto.UpdateProductRequest;
import com.acme.ecommerce.product.web.dto.VariantRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalog: reads and, as of Phase 2, writes.
 *
 * <p>Thin on the read side and that remains the correct amount of code for what it does. It exists
 * rather than being folded into the controller for two reasons that survive every phase: it is
 * where the transaction boundary belongs, and it is where the entity stops being visible - the
 * controller never sees a {@link Product}, so a column rename cannot silently change the wire
 * contract.
 *
 * <p>{@code readOnly = true} AT THE CLASS LEVEL is not cosmetic: it lets Hibernate skip
 * dirty-checking the loaded entities on the read paths and tells the JDBC driver those transactions
 * will not write, which on a replica-aware setup later is what makes routing these reads to a
 * replica a configuration change rather than a code change. Each write method below opens its own
 * {@code @Transactional}, overriding this default.
 *
 * <p>THE {@code Map<String,Object>} <-> JSON-STRING BOUNDARY FOR {@code specifications} LIVES HERE,
 * NOT ON {@link Product}. {@code Product.java} deliberately stays free of any JSON-library
 * dependency (see its own javadoc); this class already owns the entity<->DTO translation, so it is
 * the natural, and only, place that boundary belongs (docs/LLD-catalog-enrichment-and-saga.md
 * S2.1).
 *
 * <p>{@code list()} DOES NOT LOAD IMAGES, {@code findById()} DOES. Loading a product's images per
 * row on the paged list would be an extra query per row - an N+1 the LLD's S8 Performance
 * Considerations explicitly calls out to avoid. The list view stays lean (empty {@code images} on
 * every {@link ProductResponse} it returns); the detail view is rich. This mirrors the codebase's
 * existing "list is lean, detail is rich" instinct rather than introducing a new one.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProductCatalogService {

  private final ProductRepository products;
  private final ProductImageRepository images;
  private final ProductVariantRepository variantRepo;
  private final ProductNativeQueries natives;
  private final InventoryServiceClient inventoryClient;

  @org.springframework.beans.factory.annotation.Value("${INTERNAL_API_TOKEN:}")
  private String internalToken;

  private final ObjectMapper objectMapper;
  private final TimeProvider timeProvider;

  private final ProductEventPublisher events;
  private final OfferService offerService;

  /**
   * One page of the catalog.
   *
   * <p>ONE QUERY PER PAGE (plus the count). {@link Product} has no associations, so there is
   * nothing lazy to trigger during mapping and no N+1 to introduce. The gateway's {@code productCb}
   * breaker treats anything over 1s as a slow call, and this endpoint must never get near that.
   *
   * <p>{@code images} IS DELIBERATELY EMPTY ON EVERY ROW HERE - see class javadoc. A caller that
   * needs a product's images calls {@link #findById(UUID)}.
   *
   * <p>SORTING IS NORMALIZED before the query: every order puts NULLs last (Postgres's default puts
   * them FIRST on a descending sort, so "top rated" would open with unrated products), and {@code
   * sku} is appended as a final tiebreaker so that a sort on a non-unique column like price still
   * has stable page boundaries.
   *
   * @param filter optional narrowing; {@link ProductFilter#none()} for the whole catalog
   * @param pageable page number/size/sort, defaulted by the controller
   * @return the requested page, already mapped to the published DTO
   */
  public Page<ProductResponse> list(ProductFilter filter, Pageable pageable) {
    boolean hasQuery = filter.query() != null && !filter.query().isBlank();
    List<UUID> ranked = hasQuery ? natives.rankedIds(filter.query()) : null;
    Specification<Product> spec = ProductSpecifications.matching(filter, ranked);
    boolean relevance =
        hasQuery
            && pageable.getSort().stream().anyMatch(o -> o.getProperty().equals("relevance"));
    Page<Product> page;
    if (relevance) {
      Map<UUID, Integer> rank = new HashMap<>();
      for (int i = 0; i < ranked.size(); i++) {
        rank.put(ranked.get(i), i);
      }
      List<Product> all = new ArrayList<>(products.findAll(spec));
      all.sort(Comparator.comparingInt(p -> rank.getOrDefault(p.getId(), Integer.MAX_VALUE)));
      int from = (int) Math.min(all.size(), pageable.getOffset());
      int to = Math.min(all.size(), from + pageable.getPageSize());
      page = new PageImpl<>(all.subList(from, to), pageable, all.size());
    } else {
      page = products.findAll(spec, normalizeSort(pageable));
    }
    Set<UUID> withVariants =
        page.isEmpty()
            ? Set.of()
            : new HashSet<>(
                variantRepo.findProductIdsWithVariants(
                    page.getContent().stream().map(Product::getId).toList()));
    return page.map(
        product -> toResponse(product, List.of(), null, withVariants.contains(product.getId())));
  }

  /** Active products by id (lean, request order, unknown ids skipped). */
  public List<ProductResponse> findByIds(List<UUID> ids) {
    Map<UUID, Product> byId = new HashMap<>();
    products.findAllById(ids).forEach(p -> byId.put(p.getId(), p));
    Set<UUID> withVariants = new HashSet<>(variantRepo.findProductIdsWithVariants(ids));
    List<ProductResponse> out = new ArrayList<>();
    for (UUID id : new LinkedHashSet<>(ids)) {
      Product p = byId.get(id);
      if (p != null && p.isActive()) {
        out.add(toResponse(p, List.of(), null, withVariants.contains(id)));
      }
    }
    return out;
  }

  /** A single product by slug, with images and variants. */
  public ProductResponse findBySlug(String slug) {
    Product product =
        products
            .findBySlug(slug)
            .orElseThrow(() -> new ResourceNotFoundException("Product", slug));
    return detail(product);
  }

  private ProductResponse detail(Product product) {
    List<ProductVariantResponse> variants = variantResponses(product.getId(), true);
    ProductResponse response =
        toResponse(
            product,
            images.findByProductIdOrderByDisplayOrderAsc(product.getId()),
            variants,
            !variants.isEmpty());
    offerService.enrich(response);
    return response;
  }

  private List<ProductVariantResponse> variantResponses(UUID productId, boolean withStock) {
    List<ProductVariant> rows = variantRepo.findByProductIdOrderBySortOrderAscSkuAsc(productId);
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<UUID, Integer> stock =
        withStock ? variantStock(rows.stream().map(ProductVariant::getId).toList()) : Map.of();
    return rows.stream()
        .map(
            v ->
                ProductVariantResponse.builder()
                    .id(v.getId())
                    .sku(v.getSku())
                    .attributes(readSpecifications(v.getAttributes()))
                    .priceAmount(v.getPriceAmount())
                    .salePrice(v.getSalePrice())
                    .imageUrl(v.getImageUrl())
                    .active(v.isActive())
                    .sortOrder(v.getSortOrder())
                    .availableQuantity(stock.get(v.getId()))
                    .build())
        .toList();
  }

  /** Best-effort per-variant stock from inventory-service; empty on any failure. */
  private Map<UUID, Integer> variantStock(List<UUID> variantIds) {
    try {
      Map<UUID, Integer> out = new HashMap<>();
      List<InventoryDto.VariantStock> rows =
          inventoryClient.getVariantStock(variantIds, internalToken);
      if (rows != null) {
        rows.forEach(v -> out.put(v.getVariantId(), v.getQuantity()));
      }
      return out;
    } catch (RuntimeException e) {
      return Map.of();
    }
  }

  private static Map<String, List<String>> variantOptions(List<ProductVariantResponse> variants) {
    Map<String, Set<String>> options = new LinkedHashMap<>();
    for (ProductVariantResponse v : variants) {
      if (v.getAttributes() == null) {
        continue;
      }
      v.getAttributes()
          .forEach(
              (k, val) ->
                  options.computeIfAbsent(k, x -> new LinkedHashSet<>()).add(String.valueOf(val)));
    }
    Map<String, List<String>> out = new LinkedHashMap<>();
    options.forEach((k, vals) -> out.put(k, List.copyOf(vals)));
    return out;
  }

  /**
   * Full replace of the variants of a product (admin).
   *
   * @throws ResourceConflictException on a duplicate sku
   */
  @Transactional
  public List<ProductVariantResponse> replaceVariants(
      UUID productId, List<VariantRequest> requests) {
    if (!products.existsById(productId)) {
      throw new ResourceNotFoundException("Product", productId);
    }
    Instant now = timeProvider.now();
    Map<UUID, ProductVariant> existing = new HashMap<>();
    variantRepo
        .findByProductIdOrderBySortOrderAscSkuAsc(productId)
        .forEach(v -> existing.put(v.getId(), v));
    Set<String> skus = new HashSet<>();
    List<ProductVariant> keep = new ArrayList<>();
    int ord = 0;
    for (VariantRequest r : requests == null ? List.<VariantRequest>of() : requests) {
      if (r.getSku() == null || r.getSku().isBlank() || r.getSku().length() > 80) {
        throw ValidationException.forField("sku", "is required (max 80 characters)");
      }
      if (!skus.add(r.getSku().trim())) {
        throw new ResourceConflictException("Duplicate variant sku: " + r.getSku());
      }
      ProductVariant v = r.getId() == null ? null : existing.remove(r.getId());
      if (v == null) {
        v =
            ProductVariant.builder()
                .id(r.getId() != null ? r.getId() : UUID.randomUUID())
                .productId(productId)
                .createdAt(now)
                .build();
      }
      v.setSku(r.getSku().trim());
      v.setAttributes(r.getAttributes() == null ? "{}" : writeSpecifications(r.getAttributes()));
      v.setPriceAmount(r.getPriceAmount());
      v.setSalePrice(r.getSalePrice());
      v.setImageUrl(r.getImageUrl());
      v.setActive(r.getActive() == null || r.getActive());
      v.setSortOrder(r.getSortOrder() != null ? r.getSortOrder() : ord);
      v.setUpdatedAt(now);
      keep.add(v);
      ord++;
    }
    // Deletes first so a re-used sku on a different row cannot clash mid-flush.
    variantRepo.deleteAll(existing.values());
    variantRepo.flush();
    try {
      variantRepo.saveAllAndFlush(keep);
    } catch (DataIntegrityViolationException e) {
      throw new ResourceConflictException("Variant sku already exists on another product", e);
    }
    return variantResponses(productId, false);
  }

  private String uniqueSlug(String name) {
    String base =
        name == null
            ? ""
            : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    if (base.isEmpty()) {
      base = "product";
    }
    if (base.length() > 200) {
      base = base.substring(0, 200);
    }
    String slug = base;
    while (products.existsBySlug(slug)) {
      slug = base + "-" + UUID.randomUUID().toString().substring(0, 6);
    }
    return slug;
  }

  private static Pageable normalizeSort(Pageable pageable) {
    List<Sort.Order> orders = new ArrayList<>();
    pageable
        .getSort()
        .forEach(
            order -> {
              if (!order.getProperty().equals("relevance")) {
                orders.add(order.nullsLast());
              }
            });
    if (orders.stream().noneMatch(order -> order.getProperty().equals("sku"))) {
      orders.add(Sort.Order.asc("sku"));
    }
    return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders));
  }

  /**
   * A single product, including its images.
   *
   * @param id the product identifier
   * @return the product
   * @throws ResourceNotFoundException when no such product exists; {@code GlobalExceptionHandler}
   *     turns it into a 404 problem+json document. Deliberately an exception rather than an {@code
   *     Optional} returned to the controller: the translation to an HTTP status must happen in
   *     exactly one place in the platform, and that place is the advice.
   */
  public ProductResponse findById(UUID id) {
    Product product =
        products.findById(id).orElseThrow(() -> new ResourceNotFoundException("Product", id));
    return detail(product);
  }

  /**
   * Creates a product.
   *
   * @param request the product to create
   * @return the created product
   * @throws ResourceConflictException if {@code request.sku()} already exists
   */
  @Transactional
  public ProductResponse create(CreateProductRequest request) {
    return createFor(request, Offer.PLATFORM_SELLER_ID, "PLATFORM");
  }

  /** Creates a product owned by {@code sellerId}; a platform product also gets the platform offer. */
  @Transactional
  public ProductResponse createFor(CreateProductRequest request, UUID sellerId, String listedBy) {
    Instant now = timeProvider.now();
    Product product =
        Product.builder()
            .sku(request.getSku())
            .name(request.getName())
            .priceAmount(request.getPriceAmount())
            .priceCurrency(request.getPriceCurrency())
            .active(request.isActive())
            .categoryId(request.getCategoryId())
            .sellerName(request.getSellerName())
            .brand(blankToNull(request.getBrand()))
            .attributes(writeSpecifications(request.getAttributes()))
            .slug(uniqueSlug(request.getName()))
            .sellerId(sellerId)
            .listedBy(listedBy)
            .specifications(writeSpecifications(request.getSpecifications()))
            .createdAt(now)
            .updatedAt(now)
            .build();
    try {
      // saveAndFlush, NOT save. With a client-assigned @GeneratedValue(UUID) id,
      // JpaRepository.save() only enqueues the INSERT in the persistence context
      // - Hibernate is free to defer it all the way to transaction commit, which
      // happens in the surrounding @Transactional advice, AFTER this catch block
      // has already been skipped over. Without the explicit flush, a duplicate
      // sku's UNIQUE-constraint violation would surface as a raw,
      // uncaught DataIntegrityViolationException at commit time instead of the
      // clean 409 this method exists to produce - mirroring
      // RegistrationService.register()'s identical fix for the same class of bug.
      product = products.saveAndFlush(product);
    } catch (DataIntegrityViolationException e) {
      throw new ResourceConflictException("Product sku already exists: " + request.getSku(), e);
    }
    // Images reference the generated product id, so they can only be persisted
    // after the product itself has one - hence after the saveAndFlush above.
    List<ProductImage> savedImages =
        images.saveAll(toImageEntities(product.getId(), request.getImages(), now));

    ProductResponse response = toResponse(product, savedImages);
    if (Offer.PLATFORM_SELLER_ID.equals(sellerId)) {
      offerService.upsertPlatformOffer(
          product.getId(), request.getPriceAmount(), request.getPriceCurrency());
    }
    // Registered to fire AFTER this @Transactional method's transaction commits -
    // see ProductEventPublisher for the "after commit, never inside" guarantee.
    // initialQuantity rides on the PRODUCT_CREATED payload - the choreographed
    // creation-saga's entry point (docs/HLD-catalog-enrichment-and-saga.md S5).
    events.publishAfterCommit(
        ProductEventType.PRODUCT_CREATED, response, request.getInitialQuantity());

    return response;
  }

  /**
   * Updates an existing product.
   *
   * <p>IMAGES USE REPLACE, NOT DIFF, SEMANTICS: when {@code request.images()} is present, every
   * existing image row for this product is deleted and the request's images are re-inserted.
   * Acceptable for this prototype-grade feature - a smarter per-image diff is not worth the added
   * complexity until a real caller needs partial image updates.
   *
   * @param id the product identifier
   * @param request the new field values and the version the caller last read
   * @return the updated product
   * @throws ResourceNotFoundException when no such product exists
   * @throws ResourceConflictException when {@code request.version()} no longer matches the
   *     persisted product's current version. Checked explicitly, up front, rather than left to
   *     Hibernate's own {@code OptimisticLockException} on flush - both produce the same outcome,
   *     but the explicit check produces a clean 409 before any column is touched.
   */
  @Transactional
  public ProductResponse update(UUID id, UpdateProductRequest request) {

    Product product =
        products.findById(id).orElseThrow(() -> new ResourceNotFoundException("Product", id));

    if (!Objects.equals(product.getVersion(), request.getVersion())) {
      throw new ResourceConflictException(
          "Product "
              + id
              + " has version "
              + product.getVersion()
              + ", not "
              + request.getVersion());
    }

    Instant now = timeProvider.now();

    product.setName(request.getName());
    product.setPriceAmount(request.getPriceAmount());
    if (product.getSalePrice() != null) {
      // sale_price mirrors the selling price; leaving it stale made the storefront keep showing
      // the old price after an admin price edit.
      product.setSalePrice(request.getPriceAmount());
      if (product.getListPrice() != null && product.getListPrice().signum() > 0) {
        product.setDiscountPercent(
            product
                .getListPrice()
                .subtract(request.getPriceAmount())
                .multiply(java.math.BigDecimal.valueOf(100))
                .divide(product.getListPrice(), 2, RoundingMode.HALF_UP)
                .max(java.math.BigDecimal.ZERO));
      }
    }
    product.setPriceCurrency(request.getPriceCurrency());
    product.setActive(request.isActive());
    product.setCategoryId(request.getCategoryId());
    product.setSellerName(request.getSellerName());
    if (request.getBrand() != null) {
      product.setBrand(blankToNull(request.getBrand()));
    }
    if (request.getAttributes() != null) {
      product.setAttributes(writeSpecifications(request.getAttributes()));
    }
    product.setSpecifications(writeSpecifications(request.getSpecifications()));
    product.setUpdatedAt(now);

    // FLUSH BEFORE READING THE VERSION BACK. @Version is a Hibernate-managed field
    // that is only incremented in-memory at flush time; without this, the response
    // returned from this method would report the PRE-update version (flush would
    // happen later, at transaction commit, after toResponse() has already read the
    // stale value) - a client acting on that response would send the next PUT with
    // a version that is already wrong.
    Product saved = products.saveAndFlush(product);
    images.deleteByProductId(id);
    List<ProductImage> savedImages = images.saveAll(toImageEntities(id, request.getImages(), now));
    if (Offer.PLATFORM_SELLER_ID.equals(saved.getSellerId())) {
      offerService.upsertPlatformOffer(id, request.getPriceAmount(), request.getPriceCurrency());
      offerService.syncPrices(id);
      saved = products.findById(id).orElse(saved);
    }
    List<ProductVariantResponse> variantList = variantResponses(id, false);
    ProductResponse response = toResponse(saved, savedImages, variantList, !variantList.isEmpty());
    events.publishAfterCommit(ProductEventType.PRODUCT_UPDATED, response);
    return response;
  }

  /**
   * Deletes a product.
   *
   * <p>Any images belonging to it are removed by the database's {@code ON DELETE CASCADE} on {@code
   * product.product_images.product_id} - no explicit repository call needed here.
   *
   * @param id the product identifier
   * @throws ResourceNotFoundException when no such product exists
   */
  /** Writes the buy-box price into the product row (and publishes PRODUCT_UPDATED when it moved). */
  @Transactional
  public void applyBuyBoxPrice(UUID id, java.math.BigDecimal effective) {
    Product p = products.findById(id).orElse(null);
    if (p == null || p.getPriceAmount() != null && p.getPriceAmount().compareTo(effective) == 0) {
      return;
    }
    p.setPriceAmount(effective);
    if (p.getSalePrice() != null) {
      p.setSalePrice(effective);
      if (p.getListPrice() != null && p.getListPrice().signum() > 0) {
        p.setDiscountPercent(
            p.getListPrice()
                .subtract(effective)
                .multiply(java.math.BigDecimal.valueOf(100))
                .divide(p.getListPrice(), 2, RoundingMode.HALF_UP)
                .max(java.math.BigDecimal.ZERO));
      }
    }
    p.setUpdatedAt(timeProvider.now());
    Product saved = products.saveAndFlush(p);
    List<ProductVariantResponse> variantList = variantResponses(id, false);
    events.publishAfterCommit(
        ProductEventType.PRODUCT_UPDATED,
        toResponse(
            saved,
            images.findByProductIdOrderByDisplayOrderAsc(id),
            variantList,
            !variantList.isEmpty()));
  }

  @Transactional
  public void delete(UUID id) {
    Product product =
        products.findById(id).orElseThrow(() -> new ResourceNotFoundException("Product", id));
    products.delete(product);
    events.publishAfterCommit(ProductEventType.PRODUCT_DELETED, id, null);
  }

  /**
   * Applies a denormalized inventory signal. Called ONLY by {@code InventoryEventListener}, in
   * reaction to a consumed {@code INVENTORY_QUANTITY_CHANGED} event. Deliberately does NOT call
   * {@link ProductEventPublisher} - this is the service-layer half of the "no ping-pong" guarantee
   * ({@link Product#applyInventorySignal} is the domain-layer half). If the product no longer
   * exists (e.g. a lagging quantity-changed event for a {@code productId} that was deleted in the
   * interim), this is a silent no-op, not an error - the event describes a product state that has
   * legitimately since changed.
   *
   * @param productId the product whose denormalized quantity should be updated
   * @param quantity the quantity on hand at the inventory-side publish time
   */
  @Transactional
  public void applyInventorySignal(UUID productId, int quantity) {
    products
        .findById(productId)
        .ifPresent(p -> p.applyInventorySignal(quantity, timeProvider.now()));
  }

  /**
   * Overwrites the denormalized rating fields of a product with rating-service's authoritative
   * summary. Called ONLY by {@code RatingEventListener}, which fetches the summary BEFORE this
   * transaction opens so no database connection is held across the HTTP call.
   *
   * <p>Idempotent and order-independent: the summary is a full snapshot, so replays and
   * out-of-order events converge on the same values. Deliberately does NOT call {@link
   * ProductEventPublisher} - ratings changed, not the product itself. If the product no longer
   * exists, this is a silent no-op.
   *
   * @param productId the product whose denormalized rating fields should be updated
   * @param summary the current summary from rating-service
   */
  @Transactional
  public void applyRatingSummary(UUID productId, RatingSummary summary) {
    products
        .findById(productId)
        .ifPresent(
            product -> {
              int count = summary.ratingCount();
              product.setRatingCount(count);
              product.setReviewCount(summary.reviewCount());
              product.setAverageRating(
                  count > 0 && summary.averageRating() != null
                      ? summary.averageRating().setScale(2, RoundingMode.HALF_UP)
                      : null);
              product.setRatingDistribution(writeRatingDistribution(summary.ratingDistribution()));
            });
  }

  private String writeRatingDistribution(Map<Integer, Integer> distribution) {
    if (distribution == null || distribution.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(distribution);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialise rating distribution", e);
    }
  }

  private static List<ProductImage> toImageEntities(
      UUID productId, List<ProductImageRequest> requests, Instant now) {
    if (requests == null) {
      return List.of();
    }
    return requests.stream()
        .map(
            request ->
                ProductImage.builder()
                    .productId(productId)
                    .url(request.getUrl())
                    .displayOrder(request.getDisplayOrder())
                    .createdAt(now)
                    .build())
        .toList();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private ProductResponse toResponse(Product product, List<ProductImage> productImages) {
    return toResponse(product, productImages, null, false);
  }

  private ProductResponse toResponse(
      Product product,
      List<ProductImage> productImages,
      List<ProductVariantResponse> variants,
      boolean hasVariants) {
    return new ProductResponse(
        product.getId(),
        product.getSku(),
        product.getName(),
        product.getPriceAmount(),
        product.getPriceCurrency(),
        product.isActive(),
        product.getVersion(),
        product.getCategoryId(),
        product.getSellerName(),
        readSpecifications(product.getSpecifications()),
        productImages.stream().map(ProductCatalogService::toImageResponse).toList(),
        product.getLastKnownQuantity(),
        product.getDescription(),
        product.getListPrice(),
        product.getSalePrice(),
        product.getDiscountPercent(),
        product.getThumbnailUrl(),
        readVideoUrls(product.getVideoUrls()),
        product.getView360Url(),
        product.getWeightGrams(),
        product.getDimensionsLength(),
        product.getDimensionsWidth(),
        product.getDimensionsHeight(),
        product.getColor(),
        product.getMaterial(),
        product.getAverageRating(),
        product.getRatingCount(),
        product.getReviewCount(),
        readRatingDistribution(product.getRatingDistribution()),
        product.isFeatured(),
        product.getSellerRating(),
        product.isSellerVerified(),
        product.getBrand(),
        readSpecifications(product.getAttributes()),
        product.getSlug(),
        hasVariants,
        variants,
        variants == null ? null : variantOptions(variants),
        null,
        null,
        null,
        product.getSellerId(),
        product.getListedBy(),
        null,
        null);
  }

  /**
   * Reads video URLs from JSON string.
   *
   * @param videoUrlsJson the JSON string or null
   * @return list of video URLs or empty list
   */
  private List<String> readVideoUrls(String videoUrlsJson) {
    if (videoUrlsJson == null) {
      return null;
    }
    try {
      return objectMapper.readValue(videoUrlsJson, new TypeReference<List<String>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Persisted product.video_urls is not valid JSON", e);
    }
  }

  /**
   * Reads rating distribution from JSON string.
   *
   * @param ratingDistributionJson the JSON string or null
   * @return map of rating value -> count, or null
   */
  private Map<Integer, Integer> readRatingDistribution(String ratingDistributionJson) {
    if (ratingDistributionJson == null) {
      return null;
    }
    try {
      return objectMapper.readValue(
          ratingDistributionJson, new TypeReference<Map<Integer, Integer>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Persisted product.rating_distribution is not valid JSON", e);
    }
  }

  private static ProductImageResponse toImageResponse(ProductImage image) {
    return new ProductImageResponse(image.getId(), image.getUrl(), image.getDisplayOrder());
  }

  /**
   * {@code Map<String,Object>} -> JSON string, for {@code Product.builder()}/{@link
   * Product#update}.
   *
   * @throws ValidationException if the caller's map cannot be serialized to JSON - practically
   *     unreachable for a map that arrived via a JSON-deserialized request body, but handled
   *     explicitly rather than left to propagate a raw Jackson exception.
   */
  private String writeSpecifications(Map<String, Object> specifications) {
    if (specifications == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(specifications);
    } catch (JsonProcessingException e) {
      throw ValidationException.forField("specifications", "must be JSON-serializable");
    }
  }

  /**
   * JSON string -> {@code Map<String,Object>}, for {@link #toResponse}.
   *
   * @throws IllegalStateException if the persisted column is not valid JSON - an invariant this
   *     column's only writer ({@link #writeSpecifications}) already guarantees, so this signals
   *     data corruption rather than anything a caller did.
   */
  private Map<String, Object> readSpecifications(String specifications) {
    if (specifications == null) {
      return null;
    }
    try {
      return objectMapper.readValue(specifications, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Persisted product.specifications is not valid JSON", e);
    }
  }
}
