package com.acme.ecommerce.inventory.web.internal;

import com.acme.ecommerce.inventory.api.dto.InventoryResponse;
import com.acme.ecommerce.inventory.service.InventoryService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service read of per-variant stock (X-Internal-Token), keyed by variant id only. */
@RestController
@RequestMapping("/internal/inventory/variants")
public class InternalVariantStockController {

  private final InventoryService inventory;

  public InternalVariantStockController(InventoryService inventory) {
    this.inventory = inventory;
  }

  @GetMapping
  public List<InventoryResponse.VariantQuantity> variants(@RequestParam List<UUID> ids) {
    return inventory.variantQuantities(ids);
  }
}
