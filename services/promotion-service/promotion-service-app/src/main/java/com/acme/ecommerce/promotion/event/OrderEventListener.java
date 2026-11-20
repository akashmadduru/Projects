package com.acme.ecommerce.promotion.event;

import com.acme.ecommerce.promotion.config.PromotionProperties;
import com.acme.ecommerce.promotion.service.CoinService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * order.events to coins: earn on DELIVERED, reverse the earn on a full refund. The ledger is
 * idempotent, so redelivery is harmless; failures are logged and skipped (no poison loops).
 */
@Component
public class OrderEventListener {

  private final CoinService coins;
  private final PromotionProperties props;

  public OrderEventListener(CoinService coins, PromotionProperties props) {
    this.coins = coins;
    this.props = props;
  }

  /**
   * Coin credits and claw-backs are idempotent (unique ledger key, ON CONFLICT DO NOTHING), so a
   * failure propagates: the container retries, then dead-letters the event (TD-BE-17). Malformed
   * ids or timestamps go to the DLT without retries.
   */
  @KafkaListener(
      topics = "order.events",
      groupId = "promotion-service-order-listener",
      containerFactory = "orderEventListenerFactory")
  public void onOrderEvent(OrderEvent e) {
    if (e == null || e.type() == null || e.orderId() == null || e.userId() == null) {
      return;
    }
    boolean perReturn =
        "RETURN_REFUNDED".equals(e.type()) && e.returnId() != null && e.returnItemsValue() != null;
    // Per-return reversals are idempotent per return, so a late replay is safe: no age gate.
    if (!perReturn
        && e.occurredAt() != null
        && Instant.parse(e.occurredAt())
            .isBefore(
                Instant.now().minus(Duration.ofDays(props.getCoins().getMaxEventAgeDays())))) {
      return;
    }
    UUID orderId = UUID.fromString(e.orderId());
    UUID userId = UUID.fromString(e.userId());
    if ("ORDER_STATUS_CHANGED".equals(e.type()) && "DELIVERED".equals(e.status())) {
      coins.onDelivered(orderId, userId, e.totalAmount(), e.currency());
    } else if (perReturn) {
      coins.onReturnRefunded(
          orderId,
          userId,
          UUID.fromString(e.returnId()),
          e.returnItemsValue(),
          e.orderItemsValue(),
          "REFUNDED".equals(e.paymentStatus()));
    } else if ("RETURN_REFUNDED".equals(e.type()) && "REFUNDED".equals(e.paymentStatus())) {
      coins.onFullyRefunded(orderId, userId);
    }
  }
}
