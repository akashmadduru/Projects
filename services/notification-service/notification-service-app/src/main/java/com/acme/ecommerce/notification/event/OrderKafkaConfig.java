package com.acme.ecommerce.notification.event;

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

/**
 * Consumer wiring for {@code order.events}, with its own container factory.
 *
 * <p>The JSON deserializer ({@link KafkaJson#eventDeserializer}) is wrapped in an {@code
 * ErrorHandlingDeserializer} so a malformed record becomes a logged, skipped record instead of an
 * endless poll-failure loop at the same offset. Producer type headers are ignored (they name a
 * class this service does not have).
 */
@Configuration(proxyBeanMethods = false)
public class OrderKafkaConfig {

  @Bean
  public ConsumerFactory<String, OrderEvent> orderConsumerFactory(
      @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    return new DefaultKafkaConsumerFactory<>(
        props, StringDeserializer::new, () -> KafkaJson.eventDeserializer(OrderEvent.class, "*"));
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, OrderEvent> orderKafkaListenerFactory(
      ConsumerFactory<String, OrderEvent> orderConsumerFactory,
      @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
    ConcurrentKafkaListenerContainerFactory<String, OrderEvent> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(orderConsumerFactory);
    // 3 retries with exponential backoff, then <topic>.DLT.
    factory.setCommonErrorHandler(
        com.acme.ecommerce.common.web.kafka.DlqErrorHandlers.create(bootstrapServers));
    return factory;
  }
}
