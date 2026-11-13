package com.acme.ecommerce.inventory.config;

import com.acme.ecommerce.common.web.kafka.DlqErrorHandlers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;

/**
 * Dead-letter handling for the Boot-auto-configured listener container factory
 * (kafkaListenerContainerFactory): 3 retries with exponential backoff, then {@code <topic>.DLT}.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaDlqConfig {

  @Bean
  public CommonErrorHandler kafkaDlqErrorHandler(
      @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
    return DlqErrorHandlers.create(bootstrapServers);
  }
}
