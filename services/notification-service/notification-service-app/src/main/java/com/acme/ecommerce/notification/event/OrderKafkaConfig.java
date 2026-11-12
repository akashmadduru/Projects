package com.acme.ecommerce.notification.event;

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
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumer wiring for {@code order.events}, with its own container factory.
 *
 * <p>The JSON deserializer is wrapped in {@link ErrorHandlingDeserializer} so a malformed record
 * becomes a logged, skipped record instead of an endless poll-failure loop at the same offset.
 * Producer type headers are ignored (they name a class this service does not have).
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
        props,
        StringDeserializer::new,
        () -> {
          JsonDeserializer<OrderEvent> json = new JsonDeserializer<>(OrderEvent.class, false);
          json.addTrustedPackages("*");
          return new ErrorHandlingDeserializer<>(json);
        });
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, OrderEvent> orderKafkaListenerFactory(
      ConsumerFactory<String, OrderEvent> orderConsumerFactory) {
    ConcurrentKafkaListenerContainerFactory<String, OrderEvent> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(orderConsumerFactory);
    // Logs and skips after two quick retries; deserialization failures are never retried.
    factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(500L, 2L)));
    return factory;
  }
}
