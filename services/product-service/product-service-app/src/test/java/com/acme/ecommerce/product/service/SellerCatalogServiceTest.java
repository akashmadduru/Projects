package com.acme.ecommerce.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.ResourceConflictException;
import com.acme.ecommerce.common.core.error.UpstreamUnavailableException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.product.api.dto.OfferResponse;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import com.acme.ecommerce.product.client.InventoryDto;
import com.acme.ecommerce.product.client.InventoryServiceClient;
import com.acme.ecommerce.product.domain.Offer;
import com.acme.ecommerce.product.domain.Product;
import com.acme.ecommerce.product.repository.CategoryRepository;
import com.acme.ecommerce.product.repository.OfferRepository;
import com.acme.ecommerce.product.repository.ProductRepository;
import com.acme.ecommerce.product.service.OfferService.OfferInput;
import com.acme.ecommerce.product.service.SellerCatalogService.BulkResult;
import com.acme.ecommerce.product.service.SellerCatalogService.OfferRequest;
import com.acme.ecommerce.product.service.SellerCatalogService.SellerCtx;
import com.acme.ecommerce.product.service.SellerCatalogService.SellerProductRequest;
import com.acme.ecommerce.product.web.dto.CreateProductRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Offer/product create compensation (TD-P9-05) and bulk de-dup (TD-P9-04), with mocked
 * collaborators.
 */
class SellerCatalogServiceTest {

  private final UUID sellerId = UUID.randomUUID();
  private final SellerCtx ctx = new SellerCtx(sellerId, false, null);

  private SellerDirectory sellers;
  private OfferService offerService;
  private OfferRepository offers;
  private ProductRepository products;
  private ProductCatalogService catalog;
  private InventoryServiceClient inventory;
  private SellerCatalogService service;

  @BeforeEach
  void setUp() {
    sellers = mock(SellerDirectory.class);
    offerService = mock(OfferService.class);
    offers = mock(OfferRepository.class);
    products = mock(ProductRepository.class);
    catalog = mock(ProductCatalogService.class);
    inventory = mock(InventoryServiceClient.class);
    service =
        new SellerCatalogService(
            sellers,
            offerService,
            offers,
            products,
            mock(CategoryRepository.class),
            catalog,
            inventory,
            new ObjectMapper(),
            new TransactionTemplate(mock(PlatformTransactionManager.class)));
  }

  // ------------------------------------------------------------- createOffer

  private Offer offer(UUID productId) {
    return Offer.builder().id(UUID.randomUUID()).productId(productId).sellerId(sellerId).build();
  }

  private Offer stubCreate(UUID productId) {
    Offer o = offer(productId);
    when(offerService.create(eq(sellerId), any(OfferInput.class))).thenReturn(o);
    return o;
  }

  private OfferRequest request(UUID productId, Integer qty) {
    return new OfferRequest(productId, null, BigDecimal.TEN, null, "USD", 1, true, qty, "WH-1");
  }

  private static FeignException badRequest() {
    Request r =
        Request.create(Request.HttpMethod.PUT, "/x", Map.of(), null, StandardCharsets.UTF_8, null);
    return new FeignException.BadRequest("bad", r, null, Map.of());
  }

  private static FeignException serverError() {
    Request r =
        Request.create(Request.HttpMethod.PUT, "/x", Map.of(), null, StandardCharsets.UTF_8, null);
    return new FeignException.InternalServerError("boom", r, null, Map.of());
  }

  @Test
  @DisplayName("a Feign 5xx on create zeroes best-effort, discards the offer and returns 503")
  void feign5xxDiscardsAndZeroes() {
    UUID productId = UUID.randomUUID();
    Offer o = stubCreate(productId);
    // first call (the real write) fails; the zeroing call succeeds
    doThrow(serverError()).doNothing().when(inventory).putStock(any(), any());

    assertThatThrownBy(() -> service.createOffer(ctx, request(productId, 5)))
        .isInstanceOf(UpstreamUnavailableException.class);

    var calls = org.mockito.ArgumentCaptor.forClass(InventoryDto.StockWrite.class);
    verify(inventory, times(2)).putStock(calls.capture(), any());
    assertThat(calls.getAllValues().get(0).getQuantity()).isEqualTo(5);
    assertThat(calls.getAllValues().get(1).getQuantity()).isZero();
    assertThat(calls.getAllValues().get(1).getOfferId()).isEqualTo(o.getId());
    verify(offerService).discard(o.getId());
  }

  @Test
  @DisplayName("the open-circuit fallback (IllegalStateException) and a timeout both give 503")
  void fallbackAndTimeoutGive503() {
    UUID productId = UUID.randomUUID();
    Offer o = stubCreate(productId);
    doThrow(new IllegalStateException("circuit open")).when(inventory).putStock(any(), any());
    assertThatThrownBy(() -> service.createOffer(ctx, request(productId, 5)))
        .isInstanceOf(UpstreamUnavailableException.class);
    verify(offerService).discard(o.getId());

    doThrow(new RuntimeException(new java.net.SocketTimeoutException("Read timed out")))
        .when(inventory)
        .putStock(any(), any());
    assertThatThrownBy(() -> service.createOffer(ctx, request(productId, 5)))
        .isInstanceOf(UpstreamUnavailableException.class);
    verify(offerService, times(2)).discard(o.getId());
  }

  @Test
  @DisplayName("a failing zeroing call is only logged; the 503 and the discard still happen")
  void zeroingFailureOnlyLogged() {
    UUID productId = UUID.randomUUID();
    Offer o = stubCreate(productId);
    doThrow(serverError()).when(inventory).putStock(any(), any());

    assertThatThrownBy(() -> service.createOffer(ctx, request(productId, 5)))
        .isInstanceOf(UpstreamUnavailableException.class);

    verify(inventory, times(2)).putStock(any(), any());
    verify(offerService).discard(o.getId());
  }

  @Test
  @DisplayName("a failing discard is logged (OFFER_STOCKLESS) and the original 503 is rethrown")
  void discardFailureStillRethrowsOriginal() {
    UUID productId = UUID.randomUUID();
    Offer o = stubCreate(productId);
    doThrow(serverError()).when(inventory).putStock(any(), any());
    doThrow(new IllegalStateException("db down")).when(offerService).discard(o.getId());

    assertThatThrownBy(() -> service.createOffer(ctx, request(productId, 5)))
        .isInstanceOf(UpstreamUnavailableException.class);
  }

  @Test
  @DisplayName("an inventory 400 stays a 400 validation error and still discards the offer")
  void badRequestStays400() {
    UUID productId = UUID.randomUUID();
    Offer o = stubCreate(productId);
    doThrow(badRequest()).when(inventory).putStock(any(), any());

    assertThatThrownBy(() -> service.createOffer(ctx, request(productId, 5)))
        .isInstanceOf(ValidationException.class);
    verify(offerService).discard(o.getId());
  }

  @Test
  @DisplayName("no quantity: the offer is created, no stock call is made and nothing is discarded")
  void nullQuantityCreatesOfferWithoutStock() {
    UUID productId = UUID.randomUUID();
    Offer o = stubCreate(productId);
    OfferResponse view = mock(OfferResponse.class);
    when(view.getAvailableQuantity()).thenReturn(0);
    when(offerService.views(eq(productId), any())).thenReturn(List.of(view));

    var result = service.createOffer(ctx, request(productId, null));

    assertThat(result.offer().getAvailableQuantity()).isZero();
    verify(inventory, never()).putStock(any(), any());
    verify(offerService, never()).discard(any());
    assertThat(o.getId()).isNotNull();
  }

  @Test
  @DisplayName("a retried create is a 409 with no stock call and no second offer")
  void retriedCreateIs409() {
    UUID productId = UUID.randomUUID();
    when(offerService.create(eq(sellerId), any(OfferInput.class)))
        .thenThrow(new ResourceConflictException("This seller already has an offer"));

    assertThatThrownBy(() -> service.createOffer(ctx, request(productId, 5)))
        .isInstanceOf(ResourceConflictException.class);
    verify(inventory, never()).putStock(any(), any());
    verify(offerService, never()).discard(any());
  }

  // ------------------------------------------------------------ createProduct

  @Test
  @DisplayName("createProduct with a failing stock write deletes the product and returns 503")
  void createProductStockFailureDeletesProduct() {
    UUID productId = UUID.randomUUID();
    ProductResponse created = ProductResponse.builder().id(productId).build();
    when(catalog.createFor(any(CreateProductRequest.class), eq(sellerId), anyString()))
        .thenReturn(created);
    Product entity = mock(Product.class);
    when(products.findById(productId)).thenReturn(Optional.of(entity));
    Offer o = offer(productId);
    when(offers.findByProductIdAndVariantIdIsNullAndSellerId(productId, sellerId))
        .thenReturn(Optional.of(o));
    doThrow(serverError()).when(inventory).putStock(any(), any());

    var req =
        new SellerProductRequest(
            "N", null, null, null, "SKU-1", null, BigDecimal.TEN, null, "USD", 1, 3, "WH-1");
    assertThatThrownBy(() -> service.createProduct(ctx, req))
        .isInstanceOf(UpstreamUnavailableException.class);

    verify(catalog).delete(productId);
  }

  // --------------------------------------------------------------------- bulk

  private static byte[] csv(String... rows) {
    return ("sku,name,price,quantity\n" + String.join("\n", rows) + "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  /** New SKUs create a product (id = fresh, remembered by sku) and, with no offer yet, an offer. */
  private Map<String, UUID> stubNewProducts() {
    Map<String, UUID> ids = new java.util.concurrent.ConcurrentHashMap<>();
    when(catalog.createFor(any(CreateProductRequest.class), eq(sellerId), anyString()))
        .thenAnswer(
            inv -> {
              CreateProductRequest r = inv.getArgument(0);
              UUID id = UUID.randomUUID();
              ids.put(r.getSku(), id);
              return ProductResponse.builder().id(id).build();
            });
    when(products.findById(any(UUID.class))).thenAnswer(i -> Optional.of(mock(Product.class)));
    when(offerService.create(eq(sellerId), any(OfferInput.class)))
        .thenAnswer(i -> offer(((OfferInput) i.getArgument(1)).productId()));
    return ids;
  }

  @Test
  @DisplayName("later rows repeating a SKU (exact, case or whitespace variant) are row errors")
  void bulkDuplicateSkuRejected() {
    stubNewProducts();

    BulkResult result =
        service.bulk(
            ctx, csv("A1,One,10,5", "B2,Two,10,5", "a1,Dup,10,5", " B2 ,Dup,10,5", "A1,x,1,1"));

    assertThat(result.created()).isEqualTo(2);
    assertThat(result.updated()).isZero();
    assertThat(result.errors())
        .extracting(SellerCatalogService.RowError::row, SellerCatalogService.RowError::message)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(4, "duplicate sku in file"),
            org.assertj.core.groups.Tuple.tuple(5, "duplicate sku in file"),
            org.assertj.core.groups.Tuple.tuple(6, "duplicate sku in file"));
    verify(catalog, times(2)).createFor(any(CreateProductRequest.class), any(), anyString());
  }

  @Test
  @DisplayName("a row whose stock write fails is rolled back; other rows are unaffected")
  void bulkStockFailureCompensatesOnlyThatRow() {
    Map<String, UUID> ids = stubNewProducts();
    doThrow(serverError())
        .when(inventory)
        .putStock(
            org.mockito.ArgumentMatchers.argThat(
                w ->
                    w != null && ids.get("BAD") != null && ids.get("BAD").equals(w.getProductId())),
            any());

    BulkResult result = service.bulk(ctx, csv("OK1,One,10,5", "BAD,Two,10,5", "OK2,Three,10,5"));

    assertThat(result.created()).isEqualTo(2);
    assertThat(result.errors()).hasSize(1);
    assertThat(result.errors().get(0).row()).isEqualTo(3);
    verify(catalog).delete(ids.get("BAD"));
    verify(catalog, never()).delete(ids.get("OK1"));
    verify(catalog, never()).delete(ids.get("OK2"));
    verify(offerService, never()).discard(any());
  }

  @Test
  @DisplayName(
      "an existing product gaining a new offer is discarded (not deleted) on stock failure")
  void bulkOfferOnlyRowDiscardsOffer() {
    UUID productId = UUID.randomUUID();
    Product existing = mock(Product.class);
    when(existing.getId()).thenReturn(productId);
    when(existing.getSellerId()).thenReturn(sellerId);
    when(products.findBySku("E1")).thenReturn(Optional.of(existing));
    when(products.findById(productId)).thenReturn(Optional.of(existing));
    Offer o = offer(productId);
    when(offerService.create(eq(sellerId), any(OfferInput.class))).thenReturn(o);
    doThrow(serverError()).when(inventory).putStock(any(), any());

    BulkResult result = service.bulk(ctx, csv("E1,One,10,5"));

    assertThat(result.errors()).hasSize(1);
    verify(offerService).discard(o.getId());
    verify(catalog, never()).delete(any(UUID.class));
  }

  @Test
  @DisplayName("a row that only updates an existing offer reports the error and discards nothing")
  void bulkUpdateOnlyRowNotDiscarded() {
    UUID productId = UUID.randomUUID();
    Product existing = mock(Product.class);
    when(existing.getId()).thenReturn(productId);
    when(existing.getSellerId()).thenReturn(sellerId);
    when(products.findBySku("E1")).thenReturn(Optional.of(existing));
    when(products.findById(productId)).thenReturn(Optional.of(existing));
    Offer o = offer(productId);
    when(offers.findByProductIdAndVariantIdIsNullAndSellerId(productId, sellerId))
        .thenReturn(Optional.of(o));
    when(offerService.update(eq(sellerId), eq(o.getId()), any(OfferInput.class), eq(true)))
        .thenReturn(o);
    doThrow(serverError()).when(inventory).putStock(any(), any());

    BulkResult result = service.bulk(ctx, csv("E1,One,10,5"));

    assertThat(result.errors()).hasSize(1);
    verify(offerService, never()).discard(any());
    verify(catalog, never()).delete(any(UUID.class));
    verify(inventory, times(1)).putStock(any(), any());
  }
}
