package com.acme.ecommerce.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.ResourceConflictException;
import com.acme.ecommerce.common.core.error.UpstreamUnavailableException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import com.acme.ecommerce.product.client.InventoryDto;
import com.acme.ecommerce.product.client.InventoryServiceClient;
import com.acme.ecommerce.product.domain.Offer;
import com.acme.ecommerce.product.domain.Product;
import com.acme.ecommerce.product.repository.CategoryRepository;
import com.acme.ecommerce.product.repository.OfferRepository;
import com.acme.ecommerce.product.repository.ProductRepository;
import com.acme.ecommerce.product.repository.ProductVariantRepository;
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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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

  private Offer variantOffer(UUID productId, UUID variantId) {
    return Offer.builder()
        .id(UUID.randomUUID())
        .productId(productId)
        .variantId(variantId)
        .sellerId(sellerId)
        .build();
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

    InOrder order = inOrder(inventory, offerService);
    var calls = ArgumentCaptor.forClass(InventoryDto.StockWrite.class);
    order.verify(inventory, times(2)).putStock(calls.capture(), any());
    order.verify(offerService).discard(o.getId());
    assertThat(calls.getAllValues().get(0).getQuantity()).isEqualTo(5);
    assertThat(calls.getAllValues().get(1).getQuantity()).isZero();
  }

  @Test
  @DisplayName("the zeroing write mirrors the real write field for field, variant included")
  void zeroingWriteMirrorsRealWrite() {
    UUID productId = UUID.randomUUID();
    UUID variantId = UUID.randomUUID();
    Offer o = variantOffer(productId, variantId);
    when(offerService.create(eq(sellerId), any(OfferInput.class))).thenReturn(o);
    doThrow(serverError()).doNothing().when(inventory).putStock(any(), any());

    var req =
        new OfferRequest(productId, variantId, BigDecimal.TEN, null, "USD", 1, true, 5, " WH-1 ");
    assertThatThrownBy(() -> service.createOffer(ctx, req))
        .isInstanceOf(UpstreamUnavailableException.class);

    var calls = ArgumentCaptor.forClass(InventoryDto.StockWrite.class);
    verify(inventory, times(2)).putStock(calls.capture(), any());
    InventoryDto.StockWrite real = calls.getAllValues().get(0);
    InventoryDto.StockWrite zero = calls.getAllValues().get(1);
    assertThat(zero.getProductId()).isEqualTo(productId).isEqualTo(real.getProductId());
    assertThat(zero.getVariantId()).isEqualTo(variantId).isEqualTo(real.getVariantId());
    assertThat(zero.getSellerId()).isEqualTo(sellerId).isEqualTo(real.getSellerId());
    assertThat(zero.getOfferId()).isEqualTo(o.getId()).isEqualTo(real.getOfferId());
    assertThat(zero.getWarehouseCode()).isEqualTo("WH-1").isEqualTo(real.getWarehouseCode());
    assertThat(zero.getQuantity()).isZero();
    assertThat(real.getQuantity()).isEqualTo(5);
  }

  @Test
  @DisplayName("a price-sync failure after a good stock write does not undo the create")
  void syncPricesFailureDoesNotCompensate() {
    UUID productId = UUID.randomUUID();
    stubCreate(productId);
    doThrow(new IllegalStateException("sync failed")).when(offerService).syncPrices(productId);

    assertThatThrownBy(() -> service.createOffer(ctx, request(productId, 5)))
        .isInstanceOf(IllegalStateException.class);

    verify(inventory, times(1)).putStock(any(), any());
    verify(offerService, never()).discard(any());
  }

  @Test
  @DisplayName("a negative quantity is rejected before any offer is created")
  void negativeQuantityRejectedBeforeCreate() {
    UUID productId = UUID.randomUUID();

    assertThatThrownBy(() -> service.createOffer(ctx, request(productId, -1)))
        .isInstanceOf(ValidationException.class);

    verify(offerService, never()).create(any(), any());
    verify(inventory, never()).putStock(any(), any());
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

    doThrow(new RuntimeException(new SocketTimeoutException("Read timed out")))
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
  @DisplayName("an inventory 400 stays a 400: the offer is discarded and no zeroing call is made")
  void badRequestStays400() {
    UUID productId = UUID.randomUUID();
    Offer o = stubCreate(productId);
    doThrow(badRequest()).when(inventory).putStock(any(), any());

    assertThatThrownBy(() -> service.createOffer(ctx, request(productId, 5)))
        .isInstanceOf(ValidationException.class);
    verify(inventory, times(1)).putStock(any(), any());
    verify(offerService).discard(o.getId());
  }

  @Test
  @DisplayName("no quantity: offer created, no stock call, and the real OfferService reports 0")
  void nullQuantityCreatesOfferReportedAsZero() {
    UUID productId = UUID.randomUUID();
    SellerDirectory dir = mock(SellerDirectory.class);
    when(dir.info(any())).thenReturn(Map.of());
    when(inventory.stockRows(eq(productId), any())).thenReturn(List.of());
    OfferService real =
        spy(
            new OfferService(
                offers,
                products,
                mock(ProductVariantRepository.class),
                dir,
                inventory,
                mock(TimeProvider.class),
                catalog));
    Offer o = offer(productId);
    doReturn(o).when(real).create(eq(sellerId), any(OfferInput.class));
    SellerCatalogService svc =
        new SellerCatalogService(
            sellers,
            real,
            offers,
            products,
            mock(CategoryRepository.class),
            catalog,
            inventory,
            new ObjectMapper(),
            new TransactionTemplate(mock(PlatformTransactionManager.class)));

    var result = svc.createOffer(ctx, request(productId, null));

    assertThat(result.offer().getAvailableQuantity()).isZero();
    verify(inventory, never()).putStock(any(), any());
    verify(real, never()).discard(any());
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

    InOrder order = inOrder(inventory, catalog);
    order.verify(inventory, times(2)).putStock(any(), any());
    order.verify(catalog).delete(productId);
  }

  // --------------------------------------------------------------------- bulk

  private static byte[] csv(String... rows) {
    return ("sku,name,price,quantity\n" + String.join("\n", rows) + "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  /** New SKUs create a product (id = fresh, remembered by sku) and, with no offer yet, an offer. */
  private Map<String, UUID> stubNewProducts() {
    Map<String, UUID> ids = new ConcurrentHashMap<>();
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
            tuple(4, "duplicate sku in file"),
            tuple(5, "duplicate sku in file"),
            tuple(6, "duplicate sku in file"));
    verify(catalog, times(2)).createFor(any(CreateProductRequest.class), any(), anyString());
  }

  @Test
  @DisplayName("a row whose stock write fails is rolled back; other rows are unaffected")
  void bulkStockFailureCompensatesOnlyThatRow() {
    Map<String, UUID> ids = stubNewProducts();
    doThrow(serverError())
        .when(inventory)
        .putStock(
            argThat(
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
