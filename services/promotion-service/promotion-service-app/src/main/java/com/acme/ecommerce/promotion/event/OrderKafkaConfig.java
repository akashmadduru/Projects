package com.acme.ecommerce.promotion.event;

import com.acme.ecommerce.common.web.kafka.KafkaJson;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

/** Consumer wiring for {@code order.events}; malformed records are logged and skipped. */
@Configuration(proxyBeanMethods = false)
public class OrderKafkaConfig {

  @Bean
  public ConsumerFactory<String, OrderEvent> orderEventConsumerFactory(
      @Value("${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}") String bootstrapServers) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    return new DefaultKafkaConsumerFactory<>(
        props,
        StringDeserializer::new,
        () -> KafkaJson.eventDeserializer(OrderEvent.class, "com.acme.ecommerce.promotion.event"));
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, OrderEvent> orderEventListenerFactory(
      ConsumerFactory<String, OrderEvent> orderEventConsumerFactory,
      @Value("${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}") String bootstrapServers) {
    ConcurrentKafkaListenerContainerFactory<String, OrderEvent> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(orderEventConsumerFactory);
    factory.setCommonErrorHandler(
        com.acme.ecommerce.common.web.kafka.DlqErrorHandlers.create(bootstrapServers));
    return factory;
  }
}
