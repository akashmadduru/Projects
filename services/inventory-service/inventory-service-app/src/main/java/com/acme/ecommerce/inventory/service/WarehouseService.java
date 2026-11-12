package com.acme.ecommerce.inventory.service;

import com.acme.ecommerce.common.core.error.ForbiddenException;
import com.acme.ecommerce.common.core.error.ResourceConflictException;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.inventory.domain.Platform;
import com.acme.ecommerce.inventory.repository.InventoryRepository;
import com.acme.ecommerce.inventory.repository.VariantStockRepository;
import com.acme.ecommerce.inventory.repository.WarehouseRepository;
import com.acme.ecommerce.inventory.repository.WarehouseRepository.Warehouse;
import com.acme.ecommerce.inventory.repository.WarehouseStockRepository;
import com.acme.ecommerce.inventory.repository.WarehouseStockRepository.Row;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Warehouse CRUD and seller/admin stock writes. */
@Service
public class WarehouseService {

  private static final Pattern CODE = Pattern.compile("^[A-Z0-9-]{2,32}$");
  private static final Pattern PIN = Pattern.compile("^[0-9]{6}$");

  private final WarehouseRepository warehouses;
  private final WarehouseStockRepository stock;
  private final InventoryService legacy;
  private final SellerDirectory sellers;

  public WarehouseService(
      WarehouseRepository warehouses,
      WarehouseStockRepository stock,
      InventoryService legacy,
      SellerDirectory sellers) {
    this.warehouses = warehouses;
    this.stock = stock;
    this.legacy = legacy;
    this.sellers = sellers;
  }

  public List<Warehouse> list(UUID sellerId) {
    return warehouses.list(sellerId);
  }

  @Transactional
  public Warehouse create(UUID sellerId, String code, String name, String pincode, String state) {
    String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    if (!CODE.matcher(c).matches()) {
      throw ValidationException.forField("code", "must be 2-32 chars of A-Z, 0-9, '-'");
    }
    if (name == null || name.isBlank()) {
      throw ValidationException.forField("name", "is required");
    }
    if (pincode == null || !PIN.matcher(pincode.trim()).matches()) {
      throw ValidationException.forField("pincode", "must be 6 digits");
    }
    if (warehouses.byCode(c).isPresent()) {
      throw new ResourceConflictException("Warehouse code already exists: " + c);
    }
    return warehouses.insert(c, name.trim(), sellerId, pincode.trim(), state);
  }

  @Transactional
  public Warehouse update(UUID id, String name, String pincode, String state, Boolean active) {
    Warehouse w = warehouses.byId(id).orElseThrow(() -> new ResourceNotFoundException("Warehouse", id));
    String p = pincode == null ? w.pincode() : pincode.trim();
    if (!PIN.matcher(p).matches()) {
      throw ValidationException.forField("pincode", "must be 6 digits");
    }
    warehouses.update(
        id,
        name == null || name.isBlank() ? w.name() : name.trim(),
        p,
        state == null ? w.state() : state,
        active == null ? w.active() : active);
    return warehouses.byId(id).orElseThrow();
  }

  public List<Row> stockOf(UUID sellerId) {
    return stock.bySeller(sellerId);
  }

  public List<Row> stockByProduct(UUID productId) {
    return stock.byProduct(productId);
  }

  /** Seller sets stock of their own offer at one of their own warehouses. */
  @Transactional
  public Row setOfferStock(
      UUID sellerId, UUID offerId, String warehouseCode, int quantity, boolean admin) {
    SellerDirectory.OfferRef offer = sellers.offer(offerId);
    if (offer == null) {
      throw new ResourceNotFoundException("Offer", offerId);
    }
    if (!offer.sellerId().equals(sellerId) && !admin) {
      throw new ForbiddenException("Not your offer");
    }
    return writeStock(
        offer.productId(), offer.variantId(), offer.sellerId(), offerId, warehouseCode, quantity, admin);
  }

  /** Internal write used by product-service (bulk upload, offer creation). */
  @Transactional
  public Row setStock(
      UUID productId,
      UUID variantId,
      UUID sellerId,
      UUID offerId,
      String warehouseCode,
      int quantity) {
    return writeStock(productId, variantId, sellerId, offerId, warehouseCode, quantity, true);
  }

  private Row writeStock(
      UUID productId,
      UUID variantId,
      UUID sellerId,
      UUID offerId,
      String warehouseCode,
      int quantity,
      boolean anyWarehouse) {
    if (quantity < 0) {
      throw ValidationException.forField("quantity", "must be >= 0");
    }
    Warehouse w = resolveWarehouse(sellerId, warehouseCode, anyWarehouse);
    boolean platform = Platform.SELLER_ID.equals(sellerId);
    int previous =
        stock.upsert(productId, variantId, sellerId, platform ? null : offerId, w.id(), quantity);
    if (platform) {
      legacy.shiftLegacyTotals(productId, variantId, quantity - previous);
    }
    return stock.find(productId, variantId, sellerId, w.id()).orElseThrow();
  }

  private Warehouse resolveWarehouse(UUID sellerId, String code, boolean anyWarehouse) {
    if (code == null || code.isBlank()) {
      List<Warehouse> own = warehouses.list(sellerId);
      return own.stream()
          .filter(Warehouse::active)
          .findFirst()
          .orElseThrow(() -> ValidationException.forField("warehouseCode", "seller has no warehouse"));
    }
    Warehouse w =
        warehouses
            .byCode(code.trim().toUpperCase(Locale.ROOT))
            .orElseThrow(() -> ValidationException.forField("warehouseCode", "unknown warehouse"));
    if (!w.sellerId().equals(sellerId) && !anyWarehouse) {
      throw new ForbiddenException("Warehouse belongs to another seller");
    }
    return w;
  }

  /** Admin sets platform stock for a product/variant at a warehouse. */
  @Transactional
  public Row setPlatformStock(UUID productId, UUID variantId, String warehouseCode, int quantity) {
    return writeStock(productId, variantId, Platform.SELLER_ID, null, warehouseCode, quantity, true);
  }
}
