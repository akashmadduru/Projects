package com.acme.ecommerce.cart.alert;

import com.acme.ecommerce.cart.alert.AlertRepository.Alert;
import com.acme.ecommerce.cart.client.NotificationFeignClient;
import com.acme.ecommerce.cart.client.NotificationFeignClient.EmailBody;
import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Alert use cases plus the event-driven triggers (one-shot; the user re-arms). */
@Service
public class AlertService {

  private static final Logger log = LoggerFactory.getLogger(AlertService.class);
  static final int MAX_ALERTS = 100;

  public record CreateAlert(
      UUID productId,
      UUID variantId,
      String type,
      BigDecimal targetPrice,
      BigDecimal currentPrice,
      String currency,
      String productName) {}

  private final AlertRepository alerts;
  private final NotificationFeignClient notifications;
  private final String storefrontUrl;

  public AlertService(
      AlertRepository alerts,
      NotificationFeignClient notifications,
      @Value("${cart.storefront-url:http://localhost:3000}") String storefrontUrl) {
    this.alerts = alerts;
    this.notifications = notifications;
    this.storefrontUrl = storefrontUrl;
  }

  @Transactional(readOnly = true)
  public List<Alert> list(UUID userId) {
    return alerts.findByUser(userId);
  }

  @Transactional
  public Alert create(UUID userId, String email, CreateAlert r) {
    if (email == null || email.isBlank()) {
      throw new CodedException(
          ErrorCode.VALIDATION_FAILED, "Your account has no email address for alerts");
    }
    if (!"PRICE_DROP".equals(r.type()) && !"BACK_IN_STOCK".equals(r.type())) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "type must be PRICE_DROP or BACK_IN_STOCK");
    }
    if (alerts.countByUser(userId) >= MAX_ALERTS) {
      throw new CodedException(ErrorCode.CART_LIMIT, "Alerts are limited to " + MAX_ALERTS);
    }
    UUID id =
        alerts.upsert(
            userId,
            email,
            r.productId(),
            r.variantId(),
            r.productName(),
            r.type(),
            r.targetPrice(),
            r.currentPrice(),
            r.currency() == null ? null : r.currency().toUpperCase());
    return alerts.findById(id);
  }

  @Transactional
  public void delete(UUID userId, UUID id) {
    alerts.delete(id, userId);
  }

  /** Called for each product update with the effective price. */
  @Transactional
  public void onPrice(UUID productId, String name, BigDecimal price, String currency) {
    if (price == null) {
      return;
    }
    for (Alert a : alerts.activeFor(productId, "PRICE_DROP")) {
      boolean hit =
          a.targetPrice() != null
              ? price.compareTo(a.targetPrice()) <= 0
              : a.lastPrice() != null && price.compareTo(a.lastPrice()) < 0;
      if (!hit) {
        if (a.lastPrice() == null || price.compareTo(a.lastPrice()) > 0) {
          alerts.updateLastPrice(a.id(), price);
        }
        continue;
      }
      Map<String, String> data = base(a, name);
      data.put("price", price.stripTrailingZeros().toPlainString());
      data.put("currency", currency == null ? "" : currency);
      if (a.lastPrice() != null) {
        data.put("oldPrice", a.lastPrice().stripTrailingZeros().toPlainString());
      }
      fire(a, "ALERT_PRICE_DROP", data, price);
    }
  }

  /** Called when stock for a product (or variant) is positive after a change. */
  @Transactional
  public void onRestock(UUID productId, UUID variantId, int quantity) {
    if (quantity <= 0) {
      return;
    }
    for (Alert a : alerts.activeFor(productId, variantId, "BACK_IN_STOCK")) {
      fire(a, "ALERT_BACK_IN_STOCK", base(a, null), null);
    }
  }

  private Map<String, String> base(Alert a, String eventName) {
    Map<String, String> data = new HashMap<>();
    String name = eventName != null ? eventName : a.productName();
    data.put("productName", name == null ? "your watched item" : name);
    data.put("name", "there");
    data.put("link", storefrontUrl + "/products/" + a.productId());
    return data;
  }

  private void fire(Alert a, String template, Map<String, String> data, BigDecimal price) {
    // At most one email per alert per 24 h (re-armed alerts can fire again only after that).
    if (a.triggeredAt() != null && a.triggeredAt().isAfter(OffsetDateTime.now().minusHours(24))) {
      return;
    }
    try {
      notifications.sendEmail(new EmailBody(a.email(), template, data));
      alerts.markTriggered(a.id(), price);
      try {
        String pname = data.getOrDefault("productName", "an item you watch");
        notifications.inbox(
            new NotificationFeignClient.InboxBody(
                a.userId().toString(),
                "ALERT",
                "ALERT_PRICE_DROP".equals(template) ? "Price drop: " + pname : "Back in stock: " + pname,
                "Tap to view the product.",
                "/products/" + a.productId()));
      } catch (RuntimeException e) {
        log.debug("Alert inbox entry failed: {}", e.toString());
      }
      log.info("Alert {} ({}) triggered", a.id(), template);
    } catch (RuntimeException e) {
      log.warn("Alert {} email failed, will retry on next event: {}", a.id(), e.toString());
    }
  }
}
