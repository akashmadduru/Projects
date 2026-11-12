package com.acme.ecommerce.payment.client;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** notification-service internal email + inbox (X-Internal-Token). Template sent as a string. */
@FeignClient(
    name = "notification-service",
    contextId = "notificationInternalClient",
    url = "${services.notification.url:}",
    configuration = OrderClientConfig.class)
public interface NotificationFeignClient {

  record Email(String to, String template, Map<String, String> data) {}

  record Inbox(String userId, String type, String title, String body, String link) {}

  @PostMapping("/internal/notifications/email")
  void email(@RequestBody Email request);

  @PostMapping("/internal/notifications/inbox")
  void inbox(@RequestBody Inbox request);
}
