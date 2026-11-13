package com.acme.ecommerce.support.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** order-service internal lookups (ticket order-ownership check, chatbot order status). */
@FeignClient(
    name = "order-service",
    contextId = "supportOrderClient",
    url = "${services.order.url:}",
    configuration = InternalTokenFeignConfig.class)
public interface OrderFeignClient {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record OrderBrief(
      UUID id,
      UUID userId,
      String status,
      String paymentStatus,
      String currency,
      BigDecimal totalAmount,
      String placedAt,
      String carrier,
      String trackingNumber,
      String estimatedDeliveryDate) {}

  @GetMapping("/internal/orders/{id}/brief")
  OrderBrief brief(@PathVariable("id") UUID id);

  @GetMapping("/internal/orders/by-user/{userId}")
  List<OrderBrief> byUser(@PathVariable("userId") UUID userId, @RequestParam("limit") int limit);
}
