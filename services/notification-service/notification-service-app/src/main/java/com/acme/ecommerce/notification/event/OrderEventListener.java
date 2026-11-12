package com.acme.ecommerce.notification.event;

import com.acme.ecommerce.notification.api.dto.EmailTemplate;
import com.acme.ecommerce.notification.config.NotificationProperties;
import com.acme.ecommerce.notification.inbox.NotificationDispatcher;
import com.acme.ecommerce.notification.inbox.PreferenceLookup;
import com.acme.ecommerce.notification.mail.EmailContent;
import com.acme.ecommerce.notification.mail.EmailRenderer;
import com.acme.ecommerce.notification.mail.MailSenderService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

  private final NotificationDispatcher dispatcher;
  private final PreferenceLookup preferences;

  public OrderEventListener(
      EmailRenderer renderer,
      MailSenderService mail,
      NotificationProperties properties,
      NotificationDispatcher dispatcher,
      PreferenceLookup preferences) {
    this.renderer = renderer;
    this.mail = mail;
    this.properties = properties;
    this.dispatcher = dispatcher;
    this.preferences = preferences;
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
      UUID userId = parseUuid(event.userId());
      boolean hasEmail = event.email() != null && !event.email().isBlank();
      if (!hasEmail && userId == null) {
        log.info("Skipping {} for order {}: no email or user on event", event.type(), event.orderId());
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
      if (userId != null) {
        // Inbox entry (+ push when pushOrders) for every order event; link is storefront-relative.
        dispatcher.notifyUser(
            userId,
            "ORDER",
            content.subject(),
            inboxBody(content.text()),
            "/orders/" + event.orderId(),
            event.eventId() == null ? null : "order:" + event.eventId());
      }
      boolean emailOrders = userId == null || preferences.get(userId).emailOrders();
      if (hasEmail && emailOrders) {
        mail.send(event.email(), content.subject(), content.text(), content.html());
        log.info("Sent {} email for order {}", event.type(), event.orderId());
      }
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
      case "ORDER_STATUS_CHANGED" -> e.isSplit() ? null : statusChanged(e, ref, orderUrl);
      case "SHIPMENT_STATUS_CHANGED" -> e.isSplit() ? shipmentChanged(e, ref, orderUrl) : null;
      case "SUBSCRIPTION_ORDER_PLACED" ->
          renderer.render(
              EmailTemplate.SUBSCRIPTION_ORDER_PLACED,
              Map.of(
                  "productName", e.firstItemName() == null ? "your subscribed item" : e.firstItemName(),
                  "link", properties.storefrontBaseUrl() + "/account/subscriptions"));
      case "SUBSCRIPTION_FAILED" ->
          renderer.render(
              EmailTemplate.SUBSCRIPTION_FAILED,
              Map.of(
                  "productName", e.firstItemName() == null ? "your subscribed item" : e.firstItemName(),
                  "link", properties.storefrontBaseUrl() + "/account/subscriptions"));
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

  /** Split orders: one email per shipment, naming the seller. Silent for the other statuses. */
  private EmailContent shipmentChanged(OrderEvent e, String ref, String orderUrl) {
    String status = e.status() == null ? "" : e.status();
    String seller =
        e.shipments().stream()
            .filter(s -> s.shipmentId() != null && s.shipmentId().equals(e.shipmentId()))
            .map(OrderEvent.ShipmentInfo::sellerName)
            .filter(n -> n != null && !n.isBlank())
            .findFirst()
            .orElse("one of our sellers");
    return switch (status) {
      case "SHIPPED" ->
          renderer.order(
              "Part of order " + ref + " has shipped",
              "A package from " + seller + " is on the way",
              "Your order " + ref + " ships in several packages. The package from " + seller
                  + " has shipped."
                  + (e.carrier() == null ? "" : "\nCarrier: " + e.carrier())
                  + (e.trackingNumber() == null ? "" : "\nTracking number: " + e.trackingNumber())
                  + (e.estimatedDeliveryDate() == null
                      ? ""
                      : "\nEstimated delivery: " + e.estimatedDeliveryDate()),
              "Track order",
              orderUrl);
      case "DELIVERED" ->
          renderer.order(
              "Part of order " + ref + " delivered",
              "A package from " + seller + " was delivered",
              "The package from " + seller + " in order " + ref
                  + " was delivered. You can return its items within 7 days.",
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

  /** Second paragraph of the rendered text body (first is the headline). */
  private static String inboxBody(String text) {
    String[] parts = text.split("\n\n", 3);
    return (parts.length > 1 ? parts[1] : text).strip();
  }

  private static UUID parseUuid(String s) {
    try {
      return s == null ? null : UUID.fromString(s);
    } catch (IllegalArgumentException ex) {
      return null;
    }
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
