package com.acme.ecommerce.cart.client;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import feign.RequestInterceptor;

/** notification-service internal email endpoint (X-Internal-Token). */
@FeignClient(
    name = "notification-service",
    contextId = "notificationClient",
    url = "${services.notification.url:}",
    configuration = NotificationFeignClient.Config.class)
public interface NotificationFeignClient {

  record EmailBody(String to, String template, Map<String, String> data) {}

  record InboxBody(String userId, String type, String title, String body, String link) {}

  @PostMapping("/internal/notifications/inbox")
  void inbox(@RequestBody InboxBody body);

  @PostMapping("/internal/notifications/email")
  void sendEmail(@RequestBody EmailBody body);

  /** Not a scanned @Configuration: only applies to this client. */
  class Config {
    @Bean
    public RequestInterceptor internalTokenInterceptor(
        @Value("${cart.internal-token:}") String token) {
      return template -> template.header("X-Internal-Token", token);
    }
  }
}
