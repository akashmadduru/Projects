package com.acme.ecommerce.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.error.ForbiddenException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.inventory.event.InventoryEventPublisher;
import com.acme.ecommerce.inventory.event.InventoryEventType;
import com.acme.ecommerce.inventory.repository.WarehouseRepository;
import com.acme.ecommerce.inventory.repository.WarehouseRepository.Warehouse;
import com.acme.ecommerce.inventory.repository.WarehouseStockRepository;
import com.acme.ecommerce.inventory.repository.WarehouseStockRepository.Row;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Seller stock writes publish one after-commit event per real change (TD-P9-05, AC-1..3). */
class WarehouseServiceTest {

  private final UUID seller = UUID.randomUUID();
  private final UUID other = UUID.randomUUID();
  private final UUID product = UUID.randomUUID();
  private final UUID variant = UUID.randomUUID();
  private final UUID offer = UUID.randomUUID();
  private final UUID whId = UUID.randomUUID();

  private WarehouseRepository warehouses;
  private WarehouseStockRepository stock;
  private SellerDirectory sellers;
  private InventoryEventPublisher events;
  private WarehouseService service;

  @BeforeEach
  void setUp() {
    warehouses = mock(WarehouseRepository.class);
    stock = mock(WarehouseStockRepository.class);
    sellers = mock(SellerDirectory.class);
    events = mock(InventoryEventPublisher.class);
    service =
        new WarehouseService(warehouses, stock, mock(InventoryService.class), sellers, events);
    when(sellers.offer(offer))
        .thenReturn(new SellerDirectory.OfferRef(offer, product, variant, seller));
    when(warehouses.list(seller))
        .thenReturn(List.of(new Warehouse(whId, "WH-1", "n", seller, "560001", "KA", true)));
    when(warehouses.byCode("WH-OTHER"))
        .thenReturn(
            Optional.of(
                new Warehouse(UUID.randomUUID(), "WH-OTHER", "n", other, "560001", "KA", true)));
    when(stock.find(any(), any(), any(), any()))
        .thenReturn(
            Optional.of(
                new Row(
                    UUID.randomUUID(),
                    product,
                    variant,
                    seller,
                    offer,
                    whId,
                    "WH-1",
                    "560001",
                    5)));
  }

  @Test
  @DisplayName("0 to N publishes exactly one seller-scoped event carrying the seller total")
  void changedQuantityPublishesOnce() {
    when(stock.upsert(product, variant, seller, offer, whId, 5)).thenReturn(0);
    when(stock.offerTotal(product, variant, seller)).thenReturn(12);

    service.setOfferStock(seller, offer, null, 5, false);

    verify(events)
        .publishAfterCommit(
            InventoryEventType.INVENTORY_QUANTITY_CHANGED, product, variant, 12, seller, offer);
    verifyNoMoreEvents();
  }

  private void verifyNoMoreEvents() {
    org.mockito.Mockito.verifyNoMoreInteractions(events);
  }

  @Test
  @DisplayName("an unchanged quantity publishes nothing")
  void unchangedQuantityPublishesNothing() {
    when(stock.upsert(product, variant, seller, offer, whId, 5)).thenReturn(5);

    service.setOfferStock(seller, offer, null, 5, false);

    verifyNoInteractions(events);
  }

  @Test
  @DisplayName("a negative quantity is rejected and publishes nothing")
  void negativeQuantityPublishesNothing() {
    assertThatThrownBy(() -> service.setOfferStock(seller, offer, null, -1, false))
        .isInstanceOf(ValidationException.class);

    verifyNoInteractions(events);
    verify(stock, never()).upsert(any(), any(), any(), any(), any(), anyInt());
  }

  @Test
  @DisplayName("a warehouse of another seller is forbidden and publishes nothing")
  void forbiddenWarehousePublishesNothing() {
    assertThatThrownBy(() -> service.setOfferStock(seller, offer, "WH-OTHER", 5, false))
        .isInstanceOf(ForbiddenException.class);

    verifyNoInteractions(events);
  }

  @Test
  @DisplayName("seller B writing seller A's offer is forbidden: no write, no event")
  void otherSellersOfferIsForbidden() {
    assertThatThrownBy(() -> service.setOfferStock(other, offer, null, 5, false))
        .isInstanceOf(ForbiddenException.class);

    verify(stock, never()).upsert(any(), any(), any(), any(), any(), anyInt());
    verifyNoInteractions(events);
  }

  @Test
  @DisplayName("a failure while queueing the event does not fail the write")
  void publishFailureDoesNotFailWrite() {
    when(stock.upsert(product, variant, seller, offer, whId, 5)).thenReturn(0);
    doThrow(new IllegalStateException("no tx"))
        .when(events)
        .publishAfterCommit(any(), any(), any(), any(), any(), any());

    Row row = service.setOfferStock(seller, offer, null, 5, false);

    assertThat(row).isNotNull();
  }
}
