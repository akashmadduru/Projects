package com.acme.ecommerce.common.web.kafka;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Shared dead-letter wiring for every {@code @KafkaListener} container factory: 3 retries with
 * exponential backoff (1s, 2s, 4s), then the record is published to {@code <topic>.DLT} and the
 * offset moves on. Deserialisation failures (ErrorHandlingDeserializer) are not retried by {@link
 * DefaultErrorHandler} and go straight to the DLT with their original bytes.
 *
 * <p>Listeners must let failures propagate: one that catches and logs everything bypasses both the
 * retries and the DLT, and the event is lost (TD-BE-17, fixed 2026-11-17). Malformed event data
 * ({@link IllegalArgumentException}, for example a bad UUID, and {@link
 * java.time.DateTimeException}) cannot succeed on retry, so it goes to the DLT at once.
 */
public final class DlqErrorHandlers {

  private static final Logger log = LoggerFactory.getLogger(DlqErrorHandlers.class);

  private DlqErrorHandlers() {}

  public static DefaultErrorHandler create(String bootstrapServers) {
    Map<String, Object> base = new HashMap<>();
    base.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    base.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);

    // Raw bytes (deserialisation failures) and arbitrary payload objects (listener failures)
    // need different value serialisers - DeadLetterPublishingRecoverer picks by value class.
    KafkaTemplate<String, byte[]> bytes =
        new KafkaTemplate<String, byte[]>(
            new DefaultKafkaProducerFactory<String, byte[]>(
                base, new StringSerializer(), new ByteArraySerializer()));
    KafkaTemplate<String, Object> objects =
        new KafkaTemplate<String, Object>(
            new DefaultKafkaProducerFactory<String, Object>(
                base, new StringSerializer(), KafkaJson.payloadSerializer()));

    Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
    templates.put(byte[].class, bytes);
    templates.put(Object.class, objects);

    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            templates,
            (record, ex) -> {
              log.error(
                  "Dead-lettering record topic={} partition={} offset={} -> {}.DLT: {}",
                  record.topic(),
                  record.partition(),
                  record.offset(),
                  record.topic(),
                  ex.toString());
              return new org.apache.kafka.common.TopicPartition(record.topic() + ".DLT", -1);
            });

    ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
    backOff.setInitialInterval(1000L);
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(10_000L);
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(
        IllegalArgumentException.class, java.time.DateTimeException.class);
    return handler;
  }
}
