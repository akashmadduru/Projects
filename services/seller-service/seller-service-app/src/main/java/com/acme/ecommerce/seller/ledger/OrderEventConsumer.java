package com.acme.ecommerce.seller.ledger;

import com.acme.ecommerce.seller.ledger.LedgerService.Item;
import com.acme.ecommerce.seller.ledger.LedgerService.Part;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

/**
 * order.events -> settlement ledger. Tolerates an absent {@code shipments} array (whole order is
 * then attributed to the platform seller, shipmentId = orderId).
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
public class OrderEventConsumer {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record OrderEvt(
      String type, String orderId, String status, String paymentStatus, BigDecimal totalAmount, String currency, List<Part> shipments,
      String shipmentId, String returnId, String returnShipmentId, BigDecimal refundAmount) {}

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, OrderEvt> sellerOrderFactory(
      @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String servers) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    DefaultKafkaConsumerFactory<String, OrderEvt> cf =
        new DefaultKafkaConsumerFactory<>(
            props,
            StringDeserializer::new,
            () -> {
              JsonDeserializer<OrderEvt> json = new JsonDeserializer<>(OrderEvt.class, false);
              json.addTrustedPackages("*");
              return new ErrorHandlingDeserializer<>(json);
            });
    ConcurrentKafkaListenerContainerFactory<String, OrderEvt> f = new ConcurrentKafkaListenerContainerFactory<>();
    f.setConsumerFactory(cf);
    f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(500L, 2L)));
    return f;
  }

  @Component
  public static class Listener {
    private static final Logger log = LoggerFactory.getLogger(Listener.class);
    private static final Set<String> REFUNDED = Set.of("RETURNED", "REFUNDED", "RETURN_REFUNDED");
    private final LedgerService ledger;
    private final String platformSeller;

    public Listener(LedgerService ledger, @Value("${seller.platform-seller-id}") String platformSeller) {
      this.ledger = ledger;
      this.platformSeller = platformSeller;
    }

    @KafkaListener(topics = "order.events", groupId = "seller-service-ledger", containerFactory = "sellerOrderFactory")
    public void onEvent(OrderEvt e) {
      try {
        if (e == null || e.orderId() == null) {
          return;
        }
        UUID orderId = UUID.fromString(e.orderId());
        String currency = e.currency() == null ? "USD" : e.currency();
        List<Part> parts = e.shipments() == null ? List.of() : e.shipments();
        if ("RETURN_REFUNDED".equals(e.type())) {
          if (e.returnShipmentId() != null && e.returnId() != null && e.refundAmount() != null) {
            ledger.reverseReturn(orderId, UUID.fromString(e.returnShipmentId()), UUID.fromString(e.returnId()), e.refundAmount(), currency);
            return;
          }
          List<UUID> targets = parts.stream().filter(p -> REFUNDED.contains(p.status())).map(p -> UUID.fromString(p.shipmentId())).toList();
          if (!targets.isEmpty()) {
            ledger.reverseRefund(orderId, targets);
          } else if ("REFUNDED".equals(e.paymentStatus())) {
            ledger.reverseRefund(orderId, null);
          }
          return;
        }
        if (parts.isEmpty()) {
          if ("DELIVERED".equals(e.status()) && e.totalAmount() != null) {
            ledger.recordDelivered(orderId, new Part(e.orderId(), platformSeller, "DELIVERED", e.totalAmount(), List.<Item>of()), currency);
          }
          return;
        }
        for (Part p : parts) {
          if ("DELIVERED".equals(p.status()) && p.shipmentId() != null) {
            ledger.recordDelivered(orderId, p, currency);
          }
        }
      } catch (RuntimeException ex) {
        log.error("Could not process order event: {}", ex.toString());
      }
    }
  }
}
