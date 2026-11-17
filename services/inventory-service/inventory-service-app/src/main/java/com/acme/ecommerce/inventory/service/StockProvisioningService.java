package com.acme.ecommerce.inventory.service;

import com.acme.ecommerce.inventory.config.InventoryProperties;
import com.acme.ecommerce.inventory.domain.Platform;
import com.acme.ecommerce.inventory.repository.InventoryRepository;
import com.acme.ecommerce.inventory.repository.VariantStockRepository;
import com.acme.ecommerce.inventory.repository.WarehouseStockRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives every catalog product and variant a platform stock row (TD-P6-04, TD-P7-07).
 *
 * <p>Reservation fails closed on a line with no rows, so a product or variant that was never
 * provisioned (seed data, a variant added in the admin editor, a lost PRODUCT_CREATED event) would
 * be unbuyable and invisible as such. product-service's catalog sync calls this with its active
 * products and variants; a missing row is created in the default warehouse with {@code
 * inventory.stock.default-quantity} (0 in production). Existing rows and quantities are never
 * changed, so the call is idempotent and safe to repeat.
 */
@Service
public class StockProvisioningService {

  private static final Logger log = LoggerFactory.getLogger(StockProvisioningService.class);

  public record Item(UUID productId, UUID variantId) {}

  private final WarehouseStockRepository stock;
  private final VariantStockRepository variants;
  private final InventoryService inventory;
  private final InventoryProperties properties;
  private final InventoryRepository legacyTotals;

  public StockProvisioningService(
      WarehouseStockRepository stock,
      VariantStockRepository variants,
      InventoryService inventory,
      InventoryProperties properties,
      InventoryRepository legacyTotals) {
    this.legacyTotals = legacyTotals;
    this.stock = stock;
    this.variants = variants;
    this.inventory = inventory;
    this.properties = properties;
  }

  /** Returns how many products/variants got a new row. */
  @Transactional
  public int ensure(List<Item> items) {
    int quantity = Math.max(0, properties.getStock().getDefaultQuantity());
    int created = 0;
    for (Item item : items) {
      if (item.productId() == null || stock.platformRowExists(item.productId(), item.variantId())) {
        continue;
      }
      // A legacy total that already exists (for example a product created with quantity 0, which
      // never got a warehouse row) wins over the default, so the two never disagree.
      int rowQuantity;
      if (item.variantId() == null) {
        var legacy = legacyTotals.findByProductId(item.productId());
        if (legacy.isPresent()) {
          rowQuantity = legacy.get().getQuantity();
        } else {
          inventory.provisionWithQuantity(item.productId(), quantity, null, null);
          rowQuantity = quantity;
        }
      } else {
        var legacy = variants.quantity(item.variantId());
        if (legacy.isPresent()) {
          rowQuantity = legacy.get();
        } else {
          variants.upsert(item.variantId(), item.productId(), quantity);
          rowQuantity = quantity;
        }
      }
      // provisionWithQuantity already creates the row when the quantity is positive.
      stock.insertPlatformRowIfAbsent(
          item.productId(), item.variantId(), Platform.DEFAULT_WAREHOUSE_ID, rowQuantity);
      created++;
    }
    if (created > 0) {
      log.info("Created {} missing stock row(s); new ones start at {}", created, quantity);
    }
    return created;
  }
}
