package com.acme.ecommerce.promotion.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** order-service internal reads (X-Internal-Token). */
@FeignClient(
    name = "order-service",
    contextId = "promotionOrderClient",
    url = "${services.order.url:}",
    configuration = InternalFeignConfig.class)
public interface OrderFeignClient {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record FirstOrderStatus(boolean firstOrder) {}

  @GetMapping("/internal/orders/by-user/{userId}/first-order")
  FirstOrderStatus firstOrder(@PathVariable("userId") UUID userId);
}
