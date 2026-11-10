package com.acme.ecommerce.inventory.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topic this service publishes to, mirroring product-service's own {@code
 * KafkaTopicConfig} for {@code product.events} exactly (docs/HLD-catalog-enrichment-and-saga.md
 * S8).
 *
 * <p>3 partitions, keyed by {@code productId} (see {@link InventoryEventPublisher}): all events for
 * one product land in the same partition and are therefore delivered to any single consumer group
 * member in commit order. There is no cross-topic ordering guarantee against {@code product.events}
 * - none of this service's consumer logic depends on one.
 *
 * <p>{@code replicas(1)} - single-broker KRaft prototype, same accepted durability gap as {@code
 * product.events}.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTopicConfig {

  @Bean
  public NewTopic inventoryEventsTopic() {
    return TopicBuilder.name("inventory.events").partitions(3).replicas(1).build();
  }
}
