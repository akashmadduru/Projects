package com.acme.ecommerce.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.core.error.ProblemPayload;
import com.acme.ecommerce.common.core.error.ProblemPayloadFactory;
import com.acme.ecommerce.common.core.error.ResourceConflictException;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.time.FixedTimeProvider;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import com.acme.ecommerce.product.domain.Product;
import com.acme.ecommerce.product.domain.ProductImage;
import com.acme.ecommerce.product.event.ProductEventPublisher;
import com.acme.ecommerce.product.event.ProductEventType;
import com.acme.ecommerce.product.repository.ProductImageRepository;
import com.acme.ecommerce.product.repository.ProductRepository;
import com.acme.ecommerce.product.web.dto.CreateProductRequest;
import com.acme.ecommerce.product.web.dto.ProductImageRequest;
import com.acme.ecommerce.product.web.dto.UpdateProductRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for the catalog service's read and write decisions.
 *
 * <p>No Spring context: the repository and event publisher are mocked, so what is under test is the
 * service's own decisions - duplicate sku means {@link ResourceConflictException}, a stale version
 * means the same, an absent row means {@link ResourceNotFoundException} - rather than anything
 * Spring, Hibernate or Kafka would already enforce. The full HTTP round trip, including RBAC and
 * the "after commit" publish timing, is covered by {@code ProductEndpointsIntegrationTest} and
 * {@code ProductEventPublisherTest} respectively.
 */
@ExtendWith(MockitoExtension.class)
class ProductCatalogServiceTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Mock private ProductRepository products;
  @Mock private ProductImageRepository images;
  @Mock private ProductEventPublisher events;

  // Built in @BeforeEach, NOT as a field initializer: a field initializer runs during instance
  // construction, before MockitoExtension injects @Mock fields, so `products`/`events` would still
  // be null at that point.
  private ProductCatalogService service;

  @BeforeEach
  void setUp() {
    // A real ObjectMapper, not a mock: the specifications Map<->JSON conversion is an ordinary
    // Jackson operation, not a collaborator whose interactions are worth verifying.
    service =
        new ProductCatalogService(
            products, images, new ObjectMapper(), new FixedTimeProvider(NOW), events);
  }

  @Test
  @DisplayName("an unknown id raises RESOURCE_NOT_FOUND carrying the looked-up identifier")
  void unknownIdProducesNotFoundProblem() {
    UUID missing = UUID.fromString("00000000-0000-4000-8000-0000000000ff");
    when(products.findById(missing)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.findById(missing))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Product")
        .hasMessageContaining(missing.toString());
  }

  @Test
  @DisplayName("the 404 renders as an RFC 9457 problem document with the platform's members")
  void notFoundRendersAsProblemJson() {
    UUID missing = UUID.fromString("00000000-0000-4000-8000-0000000000ff");
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    ProblemPayload payload =
        new ProblemPayloadFactory(new FixedTimeProvider(now))
            .from(new ResourceNotFoundException("Product", missing), "/api/products/" + missing);

    // The RFC 9457 core members...
    assertThat(payload.status()).isEqualTo(404);
    assertThat(payload.title()).isEqualTo("Resource not found");
    assertThat(payload.type())
        .isEqualTo("https://errors.acme-ecommerce.internal/resource-not-found");
    assertThat(payload.instance()).isEqualTo("/api/products/" + missing);
    assertThat(payload.detail()).isEqualTo("Product not found: " + missing);

    // ...plus the extension members every service in the platform emits. A client
    // branches on `code`, not on the detail text, so this is the load-bearing one.
    assertThat(payload.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.code());
    assertThat(payload.correlationId()).isNotBlank();
    assertThat(payload.timestamp()).isEqualTo(now);

    // The lookup key is attached as data, so a client never has to parse the message.
    assertThat(payload.extensions())
        .containsEntry("resourceType", "Product")
        .containsEntry("identifier", missing.toString());
  }

  @Test
  @DisplayName("create() persists the product and publishes PRODUCT_CREATED")
  void createPersistsAndPublishes() {
    when(products.saveAndFlush(any(Product.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ProductResponse response =
        service.create(
            new CreateProductRequest(
                "SKU-NEW-01",
                "New Widget",
                new BigDecimal("9.99"),
                "USD",
                true,
                10,
                null,
                null,
                null,
                null));

    assertThat(response.sku()).isEqualTo("SKU-NEW-01");
    verify(events).publishAfterCommit(ProductEventType.PRODUCT_CREATED, response, 10);
  }

  @Test
  @DisplayName(
      "create() persists category/seller/specifications/images and round-trips them through"
          + " toResponse()")
  void createPersistsEnrichmentFieldsAndRoundTripsThroughResponse() throws Exception {
    UUID categoryId = UUID.randomUUID();
    // saveAndFlush is where a real @GeneratedValue(UUID) id would be assigned; this mock has to
    // do the same by hand, since create() passes the (now-generated) id to its images.
    when(products.saveAndFlush(any(Product.class)))
        .thenAnswer(
            invocation -> {
              Product saved = invocation.getArgument(0);
              setId(saved, UUID.randomUUID());
              return saved;
            });
    when(images.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

    ProductResponse response =
        service.create(
            new CreateProductRequest(
                "SKU-RICH-01",
                "Rich Widget",
                new BigDecimal("9.99"),
                "USD",
                true,
                5,
                categoryId,
                "Acme Sellers",
                Map.of("color", "red", "weightGrams", 500),
                List.of(new ProductImageRequest("https://img/1.png", 0))));

    assertThat(response.categoryId()).isEqualTo(categoryId);
    assertThat(response.sellerName()).isEqualTo("Acme Sellers");
    assertThat(response.specifications())
        .containsEntry("color", "red")
        .containsEntry("weightGrams", 500);
    assertThat(response.images()).hasSize(1);
    assertThat(response.images().get(0).url()).isEqualTo("https://img/1.png");
    assertThat(response.images().get(0).displayOrder()).isEqualTo(0);
    // lastKnownQuantity is never touched by create() in this pass - a later pass's
    // inventory-event listener is its only writer.
    assertThat(response.lastKnownQuantity()).isNull();
  }

  @Test
  @DisplayName("create() translates a duplicate sku into RESOURCE_CONFLICT, not a raw DB exception")
  void createRejectsDuplicateSku() {
    when(products.saveAndFlush(any(Product.class)))
        .thenThrow(new DataIntegrityViolationException("products_sku_key"));

    assertThatThrownBy(
            () ->
                service.create(
                    new CreateProductRequest(
                        "SKU-DUP-01",
                        "Widget",
                        new BigDecimal("9.99"),
                        "USD",
                        true,
                        10,
                        null,
                        null,
                        null,
                        null)))
        .isInstanceOf(ResourceConflictException.class);

    verify(events, never()).publishAfterCommit(any(), any(ProductResponse.class));
  }

  @Test
  @DisplayName("update() rejects a stale version before touching any column, and never publishes")
  void updateRejectsStaleVersion() throws Exception {
    UUID id = UUID.randomUUID();
    Product product = newTestProduct();
    setVersion(product, 3L);
    when(products.findById(id)).thenReturn(Optional.of(product));

    assertThatThrownBy(
            () ->
                service.update(
                    id,
                    new UpdateProductRequest(
                        "Renamed",
                        new BigDecimal("9.99"),
                        "USD",
                        true,
                        1L,
                        null,
                        null,
                        null,
                        null)))
        .isInstanceOf(ResourceConflictException.class);

    // The stale write must never land - name is unchanged, and nothing is published.
    assertThat(product.getName()).isEqualTo("Widget");
    verify(events, never()).publishAfterCommit(any(), any(ProductResponse.class));
  }

  @Test
  @DisplayName("update() flushes before reading the version back, so the response is not stale")
  void updateReturnsPostFlushVersion() throws Exception {
    UUID id = UUID.randomUUID();
    Product product = newTestProduct();
    setVersion(product, 0L);
    when(products.findById(id)).thenReturn(Optional.of(product));
    when(products.saveAndFlush(product))
        .thenAnswer(
            invocation -> {
              setVersion(product, 1L);
              return product;
            });

    ProductResponse response =
        service.update(
            id,
            new UpdateProductRequest(
                "Renamed", new BigDecimal("19.99"), "EUR", false, 0L, null, null, null, null));

    assertThat(response.name()).isEqualTo("Renamed");
    assertThat(response.version()).isEqualTo(1L);
    verify(events).publishAfterCommit(ProductEventType.PRODUCT_UPDATED, response);
  }

  @Test
  @DisplayName("delete() removes the product and publishes PRODUCT_DELETED with no payload")
  void deletePublishesWithNullPayload() {
    UUID id = UUID.randomUUID();
    Product product = newTestProduct();
    when(products.findById(id)).thenReturn(Optional.of(product));

    service.delete(id);

    verify(events).publishAfterCommit(ProductEventType.PRODUCT_DELETED, id, null);
  }

  @Test
  @DisplayName("list() never queries images - the paged view stays lean, avoiding an N+1")
  void listDoesNotLoadImages() {
    Product product = newTestProduct();
    Pageable pageable = PageRequest.of(0, 20);
    when(products.findAll(pageable)).thenReturn(new PageImpl<>(List.of(product)));

    Page<ProductResponse> page = service.list(pageable);

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).images()).isEmpty();
    verifyNoInteractions(images);
  }

  @Test
  @DisplayName("findById() loads images ordered by displayOrder, unlike list()")
  void findByIdLoadsImages() {
    UUID id = UUID.randomUUID();
    Product product = newTestProduct();
    ProductImage image =
        ProductImage.builder()
            .productId(id)
            .url("https://img/1.png")
            .displayOrder(0)
            .createdAt(NOW)
            .build();
    when(products.findById(id)).thenReturn(Optional.of(product));
    when(images.findByProductIdOrderByDisplayOrderAsc(id)).thenReturn(List.of(image));

    ProductResponse response = service.findById(id);

    assertThat(response.images()).hasSize(1);
    assertThat(response.images().get(0).url()).isEqualTo("https://img/1.png");
  }

  /**
   * {@link ProductCatalogService#applyInventorySignal(UUID, int)} is called ONLY by {@code
   * InventoryEventListener} in reaction to a consumed {@code INVENTORY_QUANTITY_CHANGED} event (see
   * that class's own tests for dispatch) - covered here the same repository-mocked,
   * no-Spring-context way as every other write-path decision in this class.
   */
  @Nested
  @DisplayName("applyInventorySignal() - INVENTORY_QUANTITY_CHANGED consumption")
  class ApplyInventorySignal {

    @Test
    @DisplayName(
        "applies the quantity onto the matching product and never publishes to product.events -"
            + " the structural 'no ping-pong' guarantee")
    void appliesSignalWhenProductPresent() {
      UUID id = UUID.randomUUID();
      Product product = newTestProduct();
      when(products.findById(id)).thenReturn(Optional.of(product));

      service.applyInventorySignal(id, 42);

      assertThat(product.getLastKnownQuantity()).isEqualTo(42);
      verifyNoInteractions(events);
    }

    @Test
    @DisplayName(
        "no-ops when no product exists for the productId - a lagging event for an already-deleted"
            + " product")
    void noOpsWhenProductAbsent() {
      UUID id = UUID.randomUUID();
      when(products.findById(id)).thenReturn(Optional.empty());

      // Must not throw.
      service.applyInventorySignal(id, 42);

      verifyNoInteractions(events);
    }
  }

  private static Product newTestProduct() {
    return Product.builder()
        .sku("SKU-01")
        .name("Widget")
        .priceAmount(new BigDecimal("9.99"))
        .priceCurrency("USD")
        .active(true)
        .createdAt(NOW)
        .updatedAt(NOW)
        .build();
  }

  /** {@code @Version} is Hibernate-managed; a plain unit test has to set it via reflection. */
  private static void setVersion(Product product, Long version) throws Exception {
    Field field = Product.class.getDeclaredField("version");
    field.setAccessible(true);
    field.set(product, version);
  }

  /** {@code @GeneratedValue(UUID)} is Hibernate-managed; a plain unit test has to set it too. */
  private static void setId(Product product, UUID id) throws Exception {
    Field field = Product.class.getDeclaredField("id");
    field.setAccessible(true);
    field.set(product, id);
  }
}
