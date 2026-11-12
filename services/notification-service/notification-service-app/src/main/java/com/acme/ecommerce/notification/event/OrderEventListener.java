package com.acme.ecommerce.notification.event;

import com.acme.ecommerce.notification.config.NotificationProperties;
import com.acme.ecommerce.notification.mail.EmailContent;
import com.acme.ecommerce.notification.mail.EmailRenderer;
import com.acme.ecommerce.notification.mail.MailSenderService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns {@code order.events} into customer emails. Never throws: a failed or unsupported event is
 * logged and skipped (poison-message safe), because retrying an email forever is worse than
 * missing one. Tech debt: in-memory de-dupe only, no outbox/retry.
 */
@Component
public class OrderEventListener {

  private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);
  private static final int DEDUPE_CAPACITY = 2000;

  private final EmailRenderer renderer;
  private final MailSenderService mail;
  private final NotificationProperties properties;

  /** Bounded, insertion-ordered set of recently handled event ids (duplicate delivery guard). */
  private final Set<String> seen =
      java.util.Collections.newSetFromMap(
          new LinkedHashMap<String, Boolean>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
              return size() > DEDUPE_CAPACITY;
            }
          });

  public OrderEventListener(
      EmailRenderer renderer, MailSenderService mail, NotificationProperties properties) {
    this.renderer = renderer;
    this.mail = mail;
    this.properties = properties;
  }

  @KafkaListener(
      topics = "order.events",
      groupId = "notification-service-order-listener",
      containerFactory = "orderKafkaListenerFactory")
  public void onOrderEvent(OrderEvent event) {
    try {
      if (event == null || event.type() == null) {
        log.warn("Skipping undecodable or typeless order event");
        return;
      }
      if (event.email() == null || event.email().isBlank()) {
        log.info("Skipping {} for order {}: no email on event", event.type(), event.orderId());
        return;
      }
      if (event.eventId() != null) {
        synchronized (seen) {
          if (!seen.add(event.eventId())) {
            log.info("Skipping duplicate event {}", event.eventId());
            return;
          }
        }
      }
      EmailContent content = contentFor(event);
      if (content == null) {
        log.debug("No email for {} status={}", event.type(), event.status());
        return;
      }
      mail.send(event.email(), content.subject(), content.text(), content.html());
      log.info("Sent {} email for order {}", event.type(), event.orderId());
    } catch (RuntimeException e) {
      log.error("Failed to handle order event {}: {}", event == null ? null : event.eventId(), e.toString());
    }
  }

  private EmailContent contentFor(OrderEvent e) {
    String ref = "#" + shortId(e.orderId());
    String orderUrl = properties.storefrontBaseUrl() + "/orders/" + e.orderId();
    String total = money(e);
    return switch (e.type()) {
      case "ORDER_PLACED" ->
          renderer.order(
              "Order " + ref + " confirmed",
              "Thanks for your order " + ref,
              summary(e)
                  + "\nTotal: "
                  + total
                  + "\nPayment: "
                  + nullToDash(e.paymentMethod())
                  + (e.estimatedDeliveryDate() == null
                      ? ""
                      : "\nEstimated delivery: " + e.estimatedDeliveryDate()),
              "View order",
              orderUrl);
      case "ORDER_CANCELLED" ->
          renderer.order(
              "Order " + ref + " cancelled",
              "Your order " + ref + " was cancelled",
              summary(e)
                  + "\nIf you paid online, your refund will be issued to the original payment"
                  + " method.",
              "View order",
              orderUrl);
      case "ORDER_STATUS_CHANGED" -> statusChanged(e, ref, orderUrl);
      case "RETURN_REQUESTED" ->
          renderer.order(
              "Return requested for order " + ref,
              "We received your return request",
              "We will review the return for order " + ref + " and update you shortly.",
              "View order",
              orderUrl);
      case "RETURN_REFUNDED" ->
          renderer.order(
              "Refund issued for order " + ref,
              "Your refund is on its way",
              "A refund for your return on order " + ref + " has been processed.",
              "View order",
              orderUrl);
      default -> null;
    };
  }

  private EmailContent statusChanged(OrderEvent e, String ref, String orderUrl) {
    String status = e.status() == null ? "" : e.status();
    return switch (status) {
      case "SHIPPED" ->
          renderer.order(
              "Order " + ref + " has shipped",
              "Your order " + ref + " is on the way",
              summary(e)
                  + (e.carrier() == null ? "" : "\nCarrier: " + e.carrier())
                  + (e.trackingNumber() == null ? "" : "\nTracking number: " + e.trackingNumber())
                  + (e.estimatedDeliveryDate() == null
                      ? ""
                      : "\nEstimated delivery: " + e.estimatedDeliveryDate()),
              "Track order",
              orderUrl);
      case "DELIVERED" ->
          renderer.order(
              "Order " + ref + " delivered",
              "Your order " + ref + " was delivered",
              summary(e) + "\nWe hope you love it. You can return items within 7 days.",
              "View order",
              orderUrl);
      default -> null;
    };
  }

  private static String summary(OrderEvent e) {
    int count = e.itemCount() == null ? 0 : e.itemCount();
    String first = e.firstItemName() == null ? "your items" : e.firstItemName();
    return count > 1 ? first + " and " + (count - 1) + " more item(s)" : first;
  }

  private static String money(OrderEvent e) {
    BigDecimal amount = e.totalAmount();
    return amount == null ? "-" : amount.toPlainString() + " " + nullToDash(e.currency());
  }

  private static String shortId(String id) {
    return id == null ? "-" : id.substring(0, Math.min(8, id.length())).toUpperCase();
  }

  private static String nullToDash(String s) {
    return s == null ? "-" : s;
  }
}
