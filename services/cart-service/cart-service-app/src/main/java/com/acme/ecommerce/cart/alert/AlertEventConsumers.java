package com.acme.ecommerce.cart.alert;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.HashMap;
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
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka wiring + listeners for alerts: product.events (price changes) and inventory.events
 * (restocks). Own container factories with ErrorHandlingDeserializer; type headers are ignored.
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
public class AlertEventConsumers {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ProductPayload(
      String name, BigDecimal priceAmount, String priceCurrency, BigDecimal salePrice) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ProductEvt(String eventType, UUID productId, ProductPayload payload) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record InventoryEvt(
      String eventType, UUID productId, UUID variantId, Integer quantity) {}

  private static <T> ConcurrentKafkaListenerContainerFactory<String, T> factory(
      String servers, Class<T> type) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    DefaultKafkaConsumerFactory<String, T> cf =
        new DefaultKafkaConsumerFactory<>(
            props,
            StringDeserializer::new,
            () -> {
              JsonDeserializer<T> json = new JsonDeserializer<>(type, false);
              json.addTrustedPackages("*");
              return new ErrorHandlingDeserializer<>(json);
            });
    ConcurrentKafkaListenerContainerFactory<String, T> f =
        new ConcurrentKafkaListenerContainerFactory<>();
    f.setConsumerFactory(cf);
    f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(500L, 2L)));
    return f;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, ProductEvt> productAlertFactory(
      @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String servers) {
    return factory(servers, ProductEvt.class);
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, InventoryEvt> inventoryAlertFactory(
      @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String servers) {
    return factory(servers, InventoryEvt.class);
  }

  /** Listener component (separate bean so the factories above exist first). */
  @Component
  public static class Listeners {

    private static final Logger log = LoggerFactory.getLogger(Listeners.class);
    private final AlertService alerts;

    public Listeners(AlertService alerts) {
      this.alerts = alerts;
    }

    @KafkaListener(
        topics = "product.events",
        groupId = "cart-alerts-product",
        containerFactory = "productAlertFactory")
    public void onProduct(ProductEvt e) {
      if (e == null || !"PRODUCT_UPDATED".equals(e.eventType()) || e.payload() == null) {
        return;
      }
      ProductPayload p = e.payload();
      BigDecimal price =
          p.salePrice() != null && p.salePrice().signum() > 0 ? p.salePrice() : p.priceAmount();
      log.info("Product {} updated, price {}", e.productId(), price);
      alerts.onPrice(e.productId(), p.name(), price, p.priceCurrency());
    }

    @KafkaListener(
        topics = "inventory.events",
        groupId = "cart-alerts-inventory",
        containerFactory = "inventoryAlertFactory")
    public void onInventory(InventoryEvt e) {
      if (e == null
          || !"INVENTORY_QUANTITY_CHANGED".equals(e.eventType())
          || e.quantity() == null) {
        return;
      }
      alerts.onRestock(e.productId(), e.variantId(), e.quantity());
    }
  }
}
