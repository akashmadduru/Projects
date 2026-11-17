package com.acme.ecommerce.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.inventory.domain.Platform;
import com.acme.ecommerce.inventory.repository.VariantStockRepository;
import com.acme.ecommerce.inventory.repository.WarehouseStockRepository;
import com.acme.ecommerce.inventory.support.AbstractInventoryIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** TD-P9-02 step 1: legacy totals are recomputed from warehouse stock. */
@Transactional
class StockTotalsReconcilerIntegrationTest extends AbstractInventoryIntegrationTest {

  @Autowired private StockTotalsReconciler reconciler;
  @Autowired private InventoryService inventory;
  @Autowired private VariantStockRepository variants;
  @Autowired private WarehouseStockRepository stock;
  @Autowired private JdbcTemplate jdbc;

  @Test
  @DisplayName("a drifted product total is corrected and its version bumped; a correct one is not")
  void productTotals() {
    UUID drifted = UUID.randomUUID();
    UUID correct = UUID.randomUUID();
    inventory.provisionWithQuantity(drifted, 5, "Drifted", "D-1");
    inventory.provisionWithQuantity(correct, 4, "Correct", "C-1");
    jdbc.update(
        "update inventory.warehouse_stock set quantity = 9 where product_id = ? and variant_id is null",
        drifted);
    long versionBefore = version(drifted);

    StockTotalsReconciler.Result r = reconciler.reconcile();

    assertThat(r.products()).isEqualTo(1);
    assertThat(quantity(drifted)).isEqualTo(9);
    assertThat(version(drifted)).isEqualTo(versionBefore + 1);
    assertThat(quantity(correct)).isEqualTo(4);
    assertThat(reconciler.reconcile().products()).isZero();
  }

  @Test
  @DisplayName("a drifted variant total is corrected from the variant's warehouse rows")
  void variantTotals() {
    UUID product = UUID.randomUUID();
    UUID variant = UUID.randomUUID();
    variants.upsert(variant, product, 3);
    stock.insertPlatformRowIfAbsent(product, variant, Platform.DEFAULT_WAREHOUSE_ID, 7);

    StockTotalsReconciler.Result r = reconciler.reconcile();

    assertThat(r.variants()).isEqualTo(1);
    assertThat(variants.quantity(variant)).contains(7);
  }

  private int quantity(UUID productId) {
    return jdbc.queryForObject(
        "select quantity from inventory.stock_levels where product_id = ?",
        Integer.class,
        productId);
  }

  private long version(UUID productId) {
    return jdbc.queryForObject(
        "select version from inventory.stock_levels where product_id = ?", Long.class, productId);
  }
}
