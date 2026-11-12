package com.acme.ecommerce.user.client;

import com.acme.ecommerce.user.config.InternalSecurityConfig;
import feign.RequestInterceptor;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
    name = "notification-service",
    contextId = "userNotificationClient",
    url = "${services.notification.url:}",
    configuration = NotificationFeignClient.InternalFeignConfig.class)
public interface NotificationFeignClient {

  record EmailBody(String to, String template, Map<String, String> data) {}

  record InboxBody(String userId, String type, String title, String body, String link) {}

  @PostMapping("/internal/notifications/email")
  void sendEmail(@RequestBody EmailBody body);

  @PostMapping("/internal/notifications/inbox")
  void inbox(@RequestBody InboxBody body);

  class InternalFeignConfig {
    @Bean
    public RequestInterceptor userNotificationTokenInterceptor(
        @Value("${user.internal-token:}") String token) {
      return template -> template.header(InternalSecurityConfig.HEADER, token);
    }
  }
}
