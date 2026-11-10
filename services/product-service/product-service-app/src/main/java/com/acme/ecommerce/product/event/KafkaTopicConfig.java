package com.acme.ecommerce.product.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topic this service publishes to.
 *
 * <p>3 partitions, keyed by {@code productId} (see {@link ProductEventPublisher}): all events for
 * one product land in the same partition and are therefore delivered to any single consumer group
 * member in commit order, which is the ordering guarantee inventory-service's idempotent-consumer
 * logic depends on (HLD S8).
 *
 * <p>{@code replicas(1)} - single-broker KRaft prototype. A documented durability gap, not a scale
 * gap; see HLD S8.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTopicConfig {

  @Bean
  public NewTopic productEventsTopic() {
    return TopicBuilder.name("product.events").partitions(3).replicas(1).build();
  }
}
