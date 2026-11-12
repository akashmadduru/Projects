package com.acme.ecommerce.notification.client;

import feign.RequestInterceptor;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
    name = "user-service",
    contextId = "notificationUserClient",
    url = "${services.user.url:}",
    configuration = UserFeignClient.InternalFeignConfig.class)
public interface UserFeignClient {

  record Preferences(
      boolean emailOrders,
      boolean emailPromotions,
      boolean pushOrders,
      boolean pushPromotions,
      boolean sms) {}

  record Member(UUID userId, String email, String displayName) {}

  record MemberPage(List<Member> content, int page, boolean last) {}

  @GetMapping("/internal/users/{userId}/preferences")
  Preferences preferences(@PathVariable("userId") UUID userId);

  @GetMapping("/internal/users")
  MemberPage audience(
      @RequestParam("audience") String audience,
      @RequestParam("page") int page,
      @RequestParam("size") int size);

  class InternalFeignConfig {
    @Bean
    public RequestInterceptor notificationInternalTokenInterceptor(
        @Value("${notification.internal-token:}") String token) {
      return template -> template.header("X-Internal-Token", token);
    }
  }
}
