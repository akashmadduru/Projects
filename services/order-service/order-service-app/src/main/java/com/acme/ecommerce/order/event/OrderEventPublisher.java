package com.acme.ecommerce.order.event;

import com.acme.ecommerce.order.api.dto.OrderItemResponse;
import com.acme.ecommerce.order.api.dto.OrderResponse;
import com.acme.ecommerce.order.api.dto.ReturnResponse;
import com.acme.ecommerce.order.api.dto.ShipmentResponse;
import com.acme.ecommerce.order.outbox.Outbox;
import com.acme.ecommerce.order.outbox.Outbox.Kind;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publisher for {@code order.events}, through the outbox (TD-P6-01): {@link #publish} builds the
 * event as of now and records it, in the caller's transaction when there is one, so the event
 * exists if and only if the order change committed. {@link
 * com.acme.ecommerce.order.outbox.OutboxRelay} sends it with {@link #sendNow}, in order per order,
 * retrying until Kafka acknowledges it. Delivery is at least once; consumers de-duplicate on the
 * event id.
 */
@Component
public class OrderEventPublisher {

  private static final String TOPIC = "order.events";
  private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);
  private static final long SEND_TIMEOUT_SECONDS = 10;

  private final KafkaTemplate<String, OrderEvent> kafka;
  private final Outbox outbox;

  public OrderEventPublisher(KafkaTemplate<String, OrderEvent> kafka, Outbox outbox) {
    this.kafka = kafka;
    this.outbox = outbox;
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
        ret == null ? null : ret.refundAmount(),
        ret == null || ret.items() == null
            ? null
            : ret.items().stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add),
        ret == null ? null : o.subtotalAmount());
  }

  private void send(OrderEventType type, OrderResponse o, OrderEvent event) {
    record(o.id(), event);
    log.debug("Queued order event type={} orderId={}", type, o.id());
  }

  private void record(UUID aggregateId, OrderEvent event) {
    outbox.enqueue(Kind.ORDER_EVENT, aggregateId, event, "event:" + event.eventId());
  }

  /**
   * Sends one recorded event and waits for Kafka's acknowledgement; throws on failure so the relay
   * retries. The key is the aggregate id, so one order's events stay on one partition, in order.
   */
  public void sendNow(UUID aggregateId, OrderEvent event) {
    try {
      kafka.send(TOPIC, aggregateId.toString(), event).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      log.info("Published order event type={} orderId={}", event.type(), aggregateId);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while publishing an order event", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException(
          "Kafka did not acknowledge order event " + event.eventId(), e);
    }
  }

  /**
   * Subscription failure notice: there is no order, so {@code orderId} carries the subscription id
   * and the item name is the subscribed product.
   */
  public void publishSubscriptionFailed(
      UUID subscriptionId,
      UUID userId,
      String email,
      String productName,
      int failures,
      boolean paused) {
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
            null,
            null,
            null);
    record(subscriptionId, event);
  }
}
