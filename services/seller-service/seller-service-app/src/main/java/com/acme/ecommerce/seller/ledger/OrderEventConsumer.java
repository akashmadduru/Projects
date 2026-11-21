package com.acme.ecommerce.seller.ledger;

import com.acme.ecommerce.common.web.kafka.KafkaJson;
import com.acme.ecommerce.seller.ledger.LedgerService.Item;
import com.acme.ecommerce.seller.ledger.LedgerService.Part;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.stereotype.Component;

/**
 * order.events -> settlement ledger. Tolerates an absent {@code shipments} array (whole order is
 * then attributed to the platform seller, shipmentId = orderId).
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
public class OrderEventConsumer {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record OrderEvt(
      String type,
      String orderId,
      String status,
      String paymentStatus,
      BigDecimal totalAmount,
      String currency,
      List<Part> shipments,
      String shipmentId,
      String returnId,
      String returnShipmentId,
      BigDecimal refundAmount,
      BigDecimal returnItemsValue) {}

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, OrderEvt> sellerOrderFactory(
      @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String servers) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
    // earliest, not latest (TD-P9-07): a new consumer group (first deploy, or the group was reset)
    // must not skip deliveries that happened before it started, or sellers are under-paid. Safe
    // because every ledger write is idempotent (unique per shipment and type, and per return), so
    // replaying history books nothing twice. An existing group resumes from its committed offset.
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    DefaultKafkaConsumerFactory<String, OrderEvt> cf =
        new DefaultKafkaConsumerFactory<>(
            props, StringDeserializer::new, () -> KafkaJson.eventDeserializer(OrderEvt.class, "*"));
    ConcurrentKafkaListenerContainerFactory<String, OrderEvt> f =
        new ConcurrentKafkaListenerContainerFactory<>();
    f.setConsumerFactory(cf);
    f.setCommonErrorHandler(com.acme.ecommerce.common.web.kafka.DlqErrorHandlers.create(servers));
    return f;
  }

  @Component
  public static class Listener {
    private static final Logger log = LoggerFactory.getLogger(Listener.class);
    private final LedgerService ledger;
    private final String platformSeller;

    public Listener(
        LedgerService ledger, @Value("${seller.platform-seller-id}") String platformSeller) {
      this.ledger = ledger;
      this.platformSeller = platformSeller;
    }

    private static boolean wellFormed(OrderEvt e) {
      if (e.returnId() == null
          || e.returnShipmentId() == null
          || e.returnItemsValue() == null
          || e.returnItemsValue().signum() <= 0) {
        return false;
      }
      try {
        UUID.fromString(e.returnId());
        UUID.fromString(e.returnShipmentId());
        return true;
      } catch (IllegalArgumentException ex) {
        return false;
      }
    }

    @KafkaListener(
        topics = "order.events",
        groupId = "seller-service-ledger",
        containerFactory = "sellerOrderFactory")
    // Ledger writes are idempotent (unique per shipment/return and type), so failures propagate:
    // the container retries, then dead-letters the event (TD-BE-17).
    public void onEvent(OrderEvt e) {
      if (e == null || e.orderId() == null) {
        return;
      }
      UUID orderId = UUID.fromString(e.orderId());
      String currency = e.currency() == null ? "USD" : e.currency();
      List<Part> parts = e.shipments() == null ? List.of() : e.shipments();
      if (!parts.isEmpty()) {
        // the register commits first and on its own, so a failing posting below cannot lose it
        ledger.track(orderId, parts, currency);
      }
      if ("RETURN_REFUNDED".equals(e.type())) {
        if (!wellFormed(e)) {
          // No fallback to refundAmount: it includes shipping and discounts, which are never the
          // seller's. IllegalArgumentException is not retryable: straight to order.events.DLT.
          log.error(
              "LEDGER ALERT malformed RETURN_REFUNDED order={} returnId present={}"
                  + " returnShipmentId present={} returnItemsValue={} - dead-lettered, nothing posted",
              e.orderId(),
              e.returnId() != null,
              e.returnShipmentId() != null,
              e.returnItemsValue());
          throw new IllegalArgumentException("malformed RETURN_REFUNDED for order " + e.orderId());
        }
        ledger.reverseReturn(
            orderId,
            UUID.fromString(e.returnShipmentId()),
            UUID.fromString(e.returnId()),
            e.returnItemsValue(),
            currency);
        return;
      }
      if (parts.isEmpty()) {
        if ("DELIVERED".equals(e.status()) && e.totalAmount() != null) {
          ledger.recordDelivered(
              orderId,
              new Part(e.orderId(), platformSeller, "DELIVERED", e.totalAmount(), List.<Item>of()),
              currency);
        }
        return;
      }
      for (Part p : parts) {
        if ("DELIVERED".equals(p.status()) && p.shipmentId() != null) {
          ledger.recordDelivered(orderId, p, currency);
        }
      }
    }
  }
}
