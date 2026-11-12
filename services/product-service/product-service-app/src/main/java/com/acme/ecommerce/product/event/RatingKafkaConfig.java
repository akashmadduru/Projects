package com.acme.ecommerce.product.event;

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
 * Consumer wiring for {@code rating.events}, separate from the default factory (which is pinned to
 * {@link InventoryEvent} via {@code spring.json.value.default.type}).
 *
 * <p>The JSON deserializer is wrapped in {@link ErrorHandlingDeserializer} so a malformed or
 * unknown-shaped record becomes a logged, skipped record instead of an endless poll-failure loop at
 * the same offset. Type headers are ignored: the producer's {@code __TypeId__} names a class this
 * service does not have.
 */
@Configuration(proxyBeanMethods = false)
public class RatingKafkaConfig {

  @Bean
  public ConsumerFactory<String, RatingEvent> ratingConsumerFactory(
      @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    return new DefaultKafkaConsumerFactory<>(
        props,
        StringDeserializer::new,
        () -> {
          JsonDeserializer<RatingEvent> json = new JsonDeserializer<>(RatingEvent.class, false);
          json.addTrustedPackages("com.acme.ecommerce.product.event");
          return new ErrorHandlingDeserializer<>(json);
        });
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, RatingEvent> ratingKafkaListenerFactory(
      ConsumerFactory<String, RatingEvent> ratingConsumerFactory) {
    ConcurrentKafkaListenerContainerFactory<String, RatingEvent> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(ratingConsumerFactory);
    // Logs and skips after two quick retries; deserialization failures are never retried.
    factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(500L, 2L)));
    return factory;
  }
}
