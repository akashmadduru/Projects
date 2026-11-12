package com.acme.ecommerce.order.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Kafka topic declaration and the scheduler switch for the pending-payment expiry job. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class MessagingConfig {

  @Bean
  public NewTopic orderEventsTopic() {
    return TopicBuilder.name("order.events").partitions(3).replicas(1).build();
  }
}
