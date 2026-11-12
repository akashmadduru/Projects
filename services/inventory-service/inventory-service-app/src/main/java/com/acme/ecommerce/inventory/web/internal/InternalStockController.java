package com.acme.ecommerce.inventory.web.internal;

import com.acme.ecommerce.inventory.repository.WarehouseStockRepository.Row;
import com.acme.ecommerce.inventory.service.WarehouseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service stock reads/writes (X-Internal-Token). */
@RestController
@RequestMapping("/internal/inventory/stock")
public class InternalStockController {

  public record StockWrite(
      @NotNull UUID productId,
      UUID variantId,
      @NotNull UUID sellerId,
      UUID offerId,
      String warehouseCode,
      @Min(0) int quantity) {}

  private final WarehouseService service;

  public InternalStockController(WarehouseService service) {
    this.service = service;
  }

  @GetMapping("/by-product/{productId}")
  public List<Row> byProduct(@PathVariable UUID productId) {
    return service.stockByProduct(productId);
  }

  @PutMapping
  public Row put(@Valid @RequestBody StockWrite r) {
    return service.setStock(
        r.productId(), r.variantId(), r.sellerId(), r.offerId(), r.warehouseCode(), r.quantity());
  }
}
