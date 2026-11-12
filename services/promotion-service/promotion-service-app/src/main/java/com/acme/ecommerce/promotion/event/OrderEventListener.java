package com.acme.ecommerce.promotion.event;

import com.acme.ecommerce.promotion.config.PromotionProperties;
import com.acme.ecommerce.promotion.service.CoinService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * order.events to coins: earn on DELIVERED, reverse the earn on a full refund. The ledger is
 * idempotent, so redelivery is harmless; failures are logged and skipped (no poison loops).
 */
@Component
public class OrderEventListener {

  private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

  private final CoinService coins;
  private final PromotionProperties props;

  public OrderEventListener(CoinService coins, PromotionProperties props) {
    this.coins = coins;
    this.props = props;
  }

  @KafkaListener(
      topics = "order.events",
      groupId = "promotion-service-order-listener",
      containerFactory = "orderEventListenerFactory")
  public void onOrderEvent(OrderEvent e) {
    try {
      if (e == null || e.type() == null || e.orderId() == null || e.userId() == null) {
        return;
      }
      if (e.occurredAt() != null
          && Instant.parse(e.occurredAt())
              .isBefore(Instant.now().minus(Duration.ofDays(props.getCoins().getMaxEventAgeDays())))) {
        return;
      }
      UUID orderId = UUID.fromString(e.orderId());
      UUID userId = UUID.fromString(e.userId());
      if ("ORDER_STATUS_CHANGED".equals(e.type()) && "DELIVERED".equals(e.status())) {
        coins.onDelivered(orderId, userId, e.totalAmount(), e.currency());
      } else if ("RETURN_REFUNDED".equals(e.type()) && "REFUNDED".equals(e.paymentStatus())) {
        coins.onFullyRefunded(orderId, userId);
      }
    } catch (RuntimeException ex) {
      log.error("Could not process order event {}: {}", e == null ? null : e.eventId(), ex.toString());
    }
  }
}
