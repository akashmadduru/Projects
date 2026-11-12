package com.acme.ecommerce.order.event;

import com.acme.ecommerce.order.api.dto.OrderItemResponse;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.api.dto.ReturnResponse;
import com.acme.ecommerce.order.api.dto.ShipmentResponse;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Fail-open publisher for {@code order.events}. Called by the orchestration layer AFTER the
 * database transaction has committed (there is no outbox: a crash between commit and publish loses
 * the event - see tech debt).
 */
@Component
public class OrderEventPublisher {

  private static final String TOPIC = "order.events";
  private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

  private final KafkaTemplate<String, OrderEvent> kafka;

  public OrderEventPublisher(KafkaTemplate<String, OrderEvent> kafka) {
    this.kafka = kafka;
  }

  public void publish(OrderEventType type, OrderResponse o) {
    send(type, o, build(type, o, null, null));
  }

  /** SHIPMENT_STATUS_CHANGED: status/tracking fields are the shipment's own. */
  public void publishShipment(OrderEventType type, OrderResponse o, ShipmentResponse shipment) {
    send(type, o, build(type, o, shipment, null));
  }

  /** RETURN_*: carries the return id, the shipment it belongs to and the refund amount. */
  public void publishReturn(OrderEventType type, OrderResponse o, ReturnResponse ret) {
    send(type, o, build(type, o, null, ret));
  }

  private static OrderEvent build(
      OrderEventType type, OrderResponse o, ShipmentResponse shipment, ReturnResponse ret) {
    List<OrderEvent.ShipmentInfo> infos =
        o.shipments() == null
            ? List.of()
            : o.shipments().stream()
                .map(
                    s ->
                        new OrderEvent.ShipmentInfo(
                            s.id(),
                            s.sellerId(),
                            s.sellerName(),
                            s.status(),
                            s.amount(),
                            s.trackingNumber(),
                            s.carrier(),
                            s.estimatedDeliveryDate() == null
                                ? null
                                : s.estimatedDeliveryDate().toString(),
                            s.items() == null
                                ? List.<OrderEvent.ShipmentItem>of()
                                : s.items().stream()
                                    .map(
                                        i ->
                                            new OrderEvent.ShipmentItem(
                                                i.productId(),
                                                i.name(),
                                                i.quantity(),
                                                i.lineTotal()))
                                    .toList()))
                .toList();
    String status = shipment != null ? shipment.status() : o.status();
    String tracking = shipment != null ? shipment.trackingNumber() : o.trackingNumber();
    String carrier = shipment != null ? shipment.carrier() : o.carrier();
    java.time.LocalDate eta =
        shipment != null ? shipment.estimatedDeliveryDate() : o.estimatedDeliveryDate();
    return new OrderEvent(
        UUID.randomUUID(),
        type,
        o.id(),
        o.userId(),
        o.email(),
        status,
        o.paymentMethod(),
        o.totalAmount(),
        o.currency(),
        tracking,
        carrier,
        eta == null ? null : eta.toString(),
        o.items().stream().mapToLong(OrderItemResponse::quantity).sum(),
        o.items().isEmpty() ? null : o.items().get(0).name(),
        Instant.now().toString(),
        o.paymentStatus(),
        infos,
        shipment == null ? null : shipment.id(),
        ret == null ? null : ret.id(),
        ret == null ? null : ret.shipmentId(),
        ret == null ? null : ret.refundAmount());
  }

  private void send(OrderEventType type, OrderResponse o, OrderEvent event) {
    try {
      kafka.send(TOPIC, o.id().toString(), event);
      log.info("Published order event type={} orderId={}", type, o.id());
    } catch (RuntimeException e) {
      log.warn("Failed to publish order event type={} orderId={}: {}", type, o.id(), e.getMessage());
    }
  }

  /**
   * Subscription failure notice: there is no order, so {@code orderId} carries the subscription id
   * and the item name is the subscribed product.
   */
  public void publishSubscriptionFailed(
      UUID subscriptionId, UUID userId, String email, String productName, int failures, boolean paused) {
    OrderEvent event =
        new OrderEvent(
            UUID.randomUUID(),
            OrderEventType.SUBSCRIPTION_FAILED,
            subscriptionId,
            userId,
            email,
            paused ? "PAUSED" : "RETRY_TOMORROW",
            null,
            null,
            null,
            null,
            null,
            null,
            failures,
            productName,
            Instant.now().toString(),
            null,
            null,
            null,
            null,
            null,
            null);
    try {
      kafka.send(TOPIC, subscriptionId.toString(), event);
    } catch (RuntimeException e) {
      log.warn("Failed to publish subscription event {}: {}", subscriptionId, e.getMessage());
    }
  }
}
