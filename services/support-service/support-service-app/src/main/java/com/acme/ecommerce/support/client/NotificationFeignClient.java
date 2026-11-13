package com.acme.ecommerce.support.client;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** notification-service internal email endpoint (X-Internal-Token). */
@FeignClient(
    name = "notification-service",
    contextId = "supportNotificationClient",
    url = "${services.notification.url:}",
    configuration = InternalTokenFeignConfig.class)
public interface NotificationFeignClient {

  record EmailBody(String to, String template, Map<String, String> data) {}

  @PostMapping("/internal/notifications/email")
  void sendEmail(@RequestBody EmailBody body);
}
