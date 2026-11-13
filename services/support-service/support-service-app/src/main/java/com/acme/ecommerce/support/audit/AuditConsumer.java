package com.acme.ecommerce.support.audit;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

/** audit.events -> audit_log. Consumes raw JSON strings so a payload change cannot poison the topic. */
@Configuration(proxyBeanMethods = false)
@EnableKafka
public class AuditConsumer {

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> auditKafkaListenerFactory(
      @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String servers) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    ConcurrentKafkaListenerContainerFactory<String, String> f =
        new ConcurrentKafkaListenerContainerFactory<>();
    f.setConsumerFactory(
        new DefaultKafkaConsumerFactory<>(props, StringDeserializer::new, StringDeserializer::new));
    f.setCommonErrorHandler(com.acme.ecommerce.common.web.kafka.DlqErrorHandlers.create(servers));
    return f;
  }

  @Component
  public static class Listener {
    private final AuditService audit;

    public Listener(AuditService audit) {
      this.audit = audit;
    }

    @KafkaListener(
        topics = "audit.events",
        groupId = "support-service-audit",
        containerFactory = "auditKafkaListenerFactory")
    public void onEvent(String payload) {
      if (payload != null && !payload.isBlank()) {
        audit.ingest(payload);
      }
    }
  }
}
