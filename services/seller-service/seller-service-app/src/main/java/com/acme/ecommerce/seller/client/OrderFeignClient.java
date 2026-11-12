package com.acme.ecommerce.seller.client;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** order-service internal shipment lookup (used to verify feedback eligibility). */
@FeignClient(
    name = "order-service",
    contextId = "sellerOrderClient",
    url = "${services.order.url:}",
    configuration = InternalTokenFeignConfig.class)
public interface OrderFeignClient {

  record ShipmentInfo(UUID shipmentId, UUID orderId, UUID userId, UUID sellerId, String status) {}

  @GetMapping("/internal/orders/shipments/{id}")
  ShipmentInfo shipment(@PathVariable("id") UUID id);
}
