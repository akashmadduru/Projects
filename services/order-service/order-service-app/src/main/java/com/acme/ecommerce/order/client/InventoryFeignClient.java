package com.acme.ecommerce.order.client;

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

  record Item(UUID productId, int quantity) {}

  record ReserveRequest(UUID orderId, List<Item> items) {}

  record ReleaseRequest(UUID returnId, List<Item> items) {}

  @PostMapping("/internal/inventory/reservations")
  void reserve(@RequestBody ReserveRequest request);

  @DeleteMapping("/internal/inventory/reservations/{orderId}")
  void restore(@PathVariable("orderId") UUID orderId);

  @PostMapping("/internal/inventory/reservations/{orderId}/release")
  void release(@PathVariable("orderId") UUID orderId, @RequestBody ReleaseRequest request);
}
