package com.acme.ecommerce.order.event;

import com.acme.ecommerce.order.api.dto.OrderItemResponse;
import com.acme.ecommerce.order.api.dto.OrderResponse;
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
    OrderEvent event =
        new OrderEvent(
            UUID.randomUUID(),
            type,
            o.id(),
            o.userId(),
            o.email(),
            o.status(),
            o.paymentMethod(),
            o.totalAmount(),
            o.currency(),
            o.trackingNumber(),
            o.carrier(),
            o.estimatedDeliveryDate() == null ? null : o.estimatedDeliveryDate().toString(),
            o.items().stream().mapToLong(OrderItemResponse::quantity).sum(),
            o.items().isEmpty() ? null : o.items().get(0).name(),
            Instant.now().toString());
    try {
      kafka.send(TOPIC, o.id().toString(), event);
      log.info("Published order event type={} orderId={}", type, o.id());
    } catch (RuntimeException e) {
      log.warn("Failed to publish order event type={} orderId={}: {}", type, o.id(), e.getMessage());
    }
  }
}
