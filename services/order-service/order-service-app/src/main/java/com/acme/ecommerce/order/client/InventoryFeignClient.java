package com.acme.ecommerce.order.client;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** inventory-service internal reservation endpoints (X-Internal-Token). */
@FeignClient(
    name = "inventory-service",
    contextId = "inventoryReservations",
    url = "${services.inventory.url:}",
    configuration = InternalFeignConfig.class)
public interface InventoryFeignClient {

  record Item(UUID productId, UUID variantId, int quantity, UUID offerId) {
    public Item(UUID productId, UUID variantId, int quantity) {
      this(productId, variantId, quantity, null);
    }
  }

  /** {@code pincode} = ship-to pincode, used to pick the nearest warehouse. */
  record ReserveRequest(UUID orderId, List<Item> items, String pincode) {}

  /** Where (part of) a line was reserved. Absent/empty with an older inventory-service. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Allocation(
      UUID productId,
      UUID variantId,
      UUID offerId,
      UUID warehouseId,
      String warehouseCode,
      @JsonAlias({"pincode", "warehousePostalCode"}) String warehousePincode,
      int quantity) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ReserveResult(List<Allocation> allocations) {}

  record ReleaseRequest(UUID returnId, List<Item> items) {}

  @PostMapping("/internal/inventory/reservations")
  ReserveResult reserve(@RequestBody ReserveRequest request);

  @DeleteMapping("/internal/inventory/reservations/{orderId}")
  void restore(@PathVariable("orderId") UUID orderId);

  @PostMapping("/internal/inventory/reservations/{orderId}/release")
  void release(@PathVariable("orderId") UUID orderId, @RequestBody ReleaseRequest request);
}
