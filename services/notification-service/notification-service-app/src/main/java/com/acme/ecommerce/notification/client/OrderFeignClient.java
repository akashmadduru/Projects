package com.acme.ecommerce.notification.client;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
    name = "order-service",
    contextId = "notificationOrderClient",
    url = "${services.order.url:}",
    configuration = OrderFeignClient.InternalFeignConfig.class)
public interface OrderFeignClient {

  /** Shape owned by order-service (MONEY agent); parsed defensively by CampaignService. */
  @GetMapping("/internal/orders/last-order-dates")
  Object lastOrderDates(@RequestParam("userIds") String userIds);

  class InternalFeignConfig {
    @Bean
    public RequestInterceptor notificationOrderTokenInterceptor(
        @Value("${notification.internal-token:}") String token) {
      return template -> template.header("X-Internal-Token", token);
    }
  }
}
