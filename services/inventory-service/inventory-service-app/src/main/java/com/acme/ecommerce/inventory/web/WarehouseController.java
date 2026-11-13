package com.acme.ecommerce.inventory.web;

import com.acme.ecommerce.inventory.domain.Platform;
import com.acme.ecommerce.inventory.repository.WarehouseRepository.Warehouse;
import com.acme.ecommerce.inventory.repository.WarehouseStockRepository.Row;
import com.acme.ecommerce.inventory.service.SellerDirectory;
import com.acme.ecommerce.inventory.service.WarehouseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Warehouses and multi-seller stock (admin + seller). */
@RestController
public class WarehouseController {

  public record WarehouseRequest(String code, String name, UUID sellerId, String pincode, String state) {}

  public record WarehouseUpdate(String name, String pincode, String state, Boolean active) {}

  public record OfferStockRequest(@NotNull UUID offerId, String warehouseCode, @Min(0) int quantity) {}

  public record PlatformStockRequest(
      @NotNull UUID productId, UUID variantId, String warehouseCode, @Min(0) int quantity) {}

  private final WarehouseService service;
  private final SellerDirectory sellers;

  public WarehouseController(WarehouseService service, SellerDirectory sellers) {
    this.service = service;
    this.sellers = sellers;
  }

  private UUID sellerOf(Authentication auth) {
    boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    return sellers.resolveSeller(((Jwt) auth.getPrincipal()).getSubject(), admin);
  }

  private static boolean isAdmin(Authentication auth) {
    return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
  }

  @GetMapping("/api/admin/warehouses")
  @PreAuthorize("hasAnyRole('ADMIN','CATALOG_MANAGER')")
  public List<Warehouse> adminList() {
    return service.list(null);
  }

  @PostMapping("/api/admin/warehouses")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyRole('ADMIN','CATALOG_MANAGER')")
  public Warehouse adminCreate(@RequestBody WarehouseRequest r) {
    return service.create(
        r.sellerId() == null ? Platform.SELLER_ID : r.sellerId(),
        r.code(),
        r.name(),
        r.pincode(),
        r.state());
  }

  @PutMapping("/api/admin/warehouses/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','CATALOG_MANAGER')")
  public Warehouse adminUpdate(@PathVariable UUID id, @RequestBody WarehouseUpdate r) {
    return service.update(id, r.name(), r.pincode(), r.state(), r.active());
  }

  @PutMapping("/api/admin/stock")
  @PreAuthorize("hasAnyRole('ADMIN','CATALOG_MANAGER')")
  public Row adminStock(@Valid @RequestBody PlatformStockRequest r) {
    return service.setPlatformStock(r.productId(), r.variantId(), r.warehouseCode(), r.quantity());
  }

  @GetMapping("/api/seller/warehouses")
  @PreAuthorize("hasAnyRole('SELLER','ADMIN')")
  public List<Warehouse> sellerList(Authentication auth) {
    return service.list(sellerOf(auth));
  }

  @PostMapping("/api/seller/warehouses")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyRole('SELLER','ADMIN')")
  public Warehouse sellerCreate(Authentication auth, @RequestBody WarehouseRequest r) {
    return service.create(sellerOf(auth), r.code(), r.name(), r.pincode(), r.state());
  }

  @GetMapping("/api/seller/stock")
  @PreAuthorize("hasAnyRole('SELLER','ADMIN')")
  public List<Row> sellerStock(Authentication auth) {
    return service.stockOf(sellerOf(auth));
  }

  @PutMapping("/api/seller/stock")
  @PreAuthorize("hasAnyRole('SELLER','ADMIN')")
  public Row sellerSetStock(Authentication auth, @Valid @RequestBody OfferStockRequest r) {
    return service.setOfferStock(sellerOf(auth), r.offerId(), r.warehouseCode(), r.quantity(), isAdmin(auth));
  }
}
