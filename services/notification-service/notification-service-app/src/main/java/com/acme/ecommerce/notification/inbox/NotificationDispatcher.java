package com.acme.ecommerce.notification.inbox;

import com.acme.ecommerce.notification.client.UserFeignClient.Preferences;
import com.acme.ecommerce.notification.push.PushService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Inbox entry + preference-gated web push for transactional (non-campaign) notifications. */
@Component
public class NotificationDispatcher {

  private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

  private final InboxService inbox;
  private final PushService push;
  private final PreferenceLookup preferences;

  public NotificationDispatcher(
      InboxService inbox, PushService push, PreferenceLookup preferences) {
    this.inbox = inbox;
    this.push = push;
    this.preferences = preferences;
  }

  /** Never throws. Push is skipped for duplicates (dedupeKey already seen). */
  public void notifyUser(
      UUID userId, String type, String title, String body, String link, String dedupeKey) {
    try {
      boolean created = inbox.add(userId, type, title, body, link, dedupeKey);
      if (!created) {
        return;
      }
      Preferences p = preferences.get(userId);
      boolean promo = "PROMO".equals(type);
      if (promo ? p.pushPromotions() : p.pushOrders()) {
        push.sendToUser(userId, title, body, link);
      }
    } catch (RuntimeException e) {
      log.warn("Notify {} failed: {}", userId, e.toString());
    }
  }
}
