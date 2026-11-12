package com.acme.ecommerce.user.membership;

import com.acme.ecommerce.user.client.NotificationFeignClient;
import com.acme.ecommerce.user.client.NotificationFeignClient.EmailBody;
import com.acme.ecommerce.user.client.NotificationFeignClient.InboxBody;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Fail-open wrapper over notification-service: a notification failure never fails the caller. */
@Component
public class Notifier {

  private static final Logger log = LoggerFactory.getLogger(Notifier.class);

  private final NotificationFeignClient client;

  public Notifier(NotificationFeignClient client) {
    this.client = client;
  }

  public void email(String to, String template, Map<String, String> data) {
    if (to == null || to.isBlank()) {
      return;
    }
    try {
      client.sendEmail(new EmailBody(to, template, data));
    } catch (RuntimeException e) {
      log.warn("Email {} failed: {}", template, e.toString());
    }
  }

  public void inbox(UUID userId, String type, String title, String body, String link) {
    try {
      client.inbox(new InboxBody(userId.toString(), type, title, body, link));
    } catch (RuntimeException e) {
      log.warn("Inbox entry failed: {}", e.toString());
    }
  }
}
