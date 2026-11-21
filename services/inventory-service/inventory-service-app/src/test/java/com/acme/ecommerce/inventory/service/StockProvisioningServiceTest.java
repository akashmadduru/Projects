package com.acme.ecommerce.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.inventory.config.InventoryProperties;
import com.acme.ecommerce.inventory.domain.Inventory;
import com.acme.ecommerce.inventory.domain.Platform;
import com.acme.ecommerce.inventory.repository.InventoryRepository;
import com.acme.ecommerce.inventory.repository.VariantStockRepository;
import com.acme.ecommerce.inventory.repository.WarehouseStockRepository;
import com.acme.ecommerce.inventory.service.StockProvisioningService.Item;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TD-P6-04 / TD-P7-07: every product and variant gets a row; existing ones are untouched. */
class StockProvisioningServiceTest {

  private final UUID product = UUID.randomUUID();
  private final UUID variant = UUID.randomUUID();

  private WarehouseStockRepository stock;
  private VariantStockRepository variants;
  private InventoryService inventory;
  private InventoryRepository legacy;
  private StockProvisioningService service;

  @BeforeEach
  void setUp() {
    stock = mock(WarehouseStockRepository.class);
    variants = mock(VariantStockRepository.class);
    inventory = mock(InventoryService.class);
    legacy = mock(InventoryRepository.class);
    InventoryProperties props = new InventoryProperties();
    props.getStock().setDefaultQuantity(100);
    service = new StockProvisioningService(stock, variants, inventory, props, legacy);
  }

  @Test
  @DisplayName("an item that already has a platform row is left alone")
  void existingRowUntouched() {
    when(stock.platformRowExists(product, null)).thenReturn(true);

    assertThat(service.ensure(List.of(new Item(product, null)))).isZero();

    verify(inventory, never()).provisionWithQuantity(any(), any(), any(), any());
    verify(stock, never()).insertPlatformRowIfAbsent(any(), any(), any(), anyInt());
  }

  @Test
  @DisplayName("a product with no rows at all gets the default quantity")
  void newProductGetsDefault() {
    when(legacy.findByProductId(product)).thenReturn(Optional.empty());

    assertThat(service.ensure(List.of(new Item(product, null)))).isEqualTo(1);

    verify(inventory).provisionWithQuantity(product, 100, null, null);
    verify(stock).insertPlatformRowIfAbsent(product, null, Platform.DEFAULT_WAREHOUSE_ID, 100);
  }

  @Test
  @DisplayName("an existing legacy total wins over the default, so the two never disagree")
  void legacyTotalWins() {
    Inventory row = mock(Inventory.class);
    when(row.getQuantity()).thenReturn(0);
    when(legacy.findByProductId(product)).thenReturn(Optional.of(row));

    service.ensure(List.of(new Item(product, null)));

    verify(inventory, never()).provisionWithQuantity(any(), any(), any(), any());
    verify(stock).insertPlatformRowIfAbsent(product, null, Platform.DEFAULT_WAREHOUSE_ID, 0);
  }

  @Test
  @DisplayName("a new variant gets a variant total and a warehouse row")
  void newVariant() {
    when(variants.quantity(variant)).thenReturn(Optional.empty());

    assertThat(service.ensure(List.of(new Item(product, variant)))).isEqualTo(1);

    verify(variants).upsert(variant, product, 100);
    verify(stock).insertPlatformRowIfAbsent(product, variant, Platform.DEFAULT_WAREHOUSE_ID, 100);
  }
}
