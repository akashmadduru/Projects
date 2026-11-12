package com.acme.ecommerce.cart.client;

import feign.RequestInterceptor;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
    name = "user-service",
    contextId = "cartUserClient",
    url = "${services.user.url:}",
    configuration = UserFeignClient.Config.class)
public interface UserFeignClient {

  record Preferences(
      boolean emailOrders,
      boolean emailPromotions,
      boolean pushOrders,
      boolean pushPromotions,
      boolean sms) {}

  @GetMapping("/internal/users/{userId}/preferences")
  Preferences preferences(@PathVariable("userId") UUID userId);

  class Config {
    @Bean
    public RequestInterceptor cartUserTokenInterceptor(
        @Value("${cart.internal-token:}") String token) {
      return template -> template.header("X-Internal-Token", token);
    }
  }
}
