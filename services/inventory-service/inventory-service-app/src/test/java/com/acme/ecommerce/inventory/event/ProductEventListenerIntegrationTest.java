package com.acme.ecommerce.inventory.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.acme.ecommerce.inventory.service.InventoryService;
import com.acme.ecommerce.inventory.support.AbstractInventoryIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * Proves {@link ProductEventListener} dispatches each of the three event types correctly against a
 * REAL broker, not a mock of {@code KafkaTemplate}/{@code @KafkaListener} - a real {@code
 * DefaultKafkaConsumerFactory}, real deserialization (including {@code
 * spring.json.trusted.packages}), and a real listener container all participate.
 *
 * <p>{@link InventoryService} IS STILL MOCKED. This is deliberately an integration test of the
 * listener's wiring and dispatch, not a re-test of {@link InventoryService}'s own idempotency
 * decisions - those are {@code InventoryServiceTest}'s job, and duplicating them here against a
 * slower, broker-backed test would only make the suite slower without adding coverage.
 *
 * <p>{@code Mockito.timeout(...)} instead of a sleep or a polling loop - {@code @KafkaListener}
 * delivery is asynchronous to the test thread, and {@code verify(mock, timeout(millis))} polls
 * until the interaction happens or the timeout elapses, without pulling in a separate awaiting
 * library.
 */
@Testcontainers
class ProductEventListenerIntegrationTest extends AbstractInventoryIntegrationTest {

  // NOTE: NOT the same `apache/kafka:3.9.0` image infra/docker-compose.yml runs -
  // that image's startup sequence is incompatible with testcontainers-kafka
  // 2.0.5's wait strategy in this environment (times out waiting for a log line
  // the image never emits in KRaft-under-Testcontainers mode). ConfluentKafkaContainer
  // is the module's own well-exercised KRaft-mode container and is functionally
  // equivalent for this test's purposes - it only needs a broker that accepts a
  // produce and a consume, not any Confluent-specific behavior.
  @Container
  static final ConfluentKafkaContainer KAFKA =
      new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

  @MockitoBean private InventoryService inventoryService;
  @MockitoBean private InventoryEventPublisher inventoryEvents;

  private static KafkaProducer<String, String> producer;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  @BeforeAll
  static void startProducer() {
    // A plain String producer, not the application's own (consumer-only)
    // configuration - the wire format is JSON bytes either way, and this
    // keeps the test independent of any Spring-managed producer bean this
    // service does not otherwise have.
    Map<String, Object> producerProps = KafkaTestUtils.producerProps(KAFKA.getBootstrapServers());
    // KafkaTestUtils.producerProps() defaults key.serializer to IntegerSerializer
    // (it's meant for simple int-keyed test scenarios) - this producer sends the
    // productId as a String key, so both serializers must be overridden.
    producerProps.put("key.serializer", StringSerializer.class.getName());
    producerProps.put("value.serializer", StringSerializer.class.getName());
    producer = new KafkaProducer<>(producerProps);
  }

  @AfterAll
  static void stopProducer() {
    if (producer != null) {
      producer.close();
    }
  }

  @Test
  @DisplayName("PRODUCT_CREATED, consumed from a real broker, provisions inventory")
  void productCreatedIsProvisioned() throws Exception {
    UUID productId = UUID.randomUUID();
    publish(ProductEventType.PRODUCT_CREATED, productId);

    verify(inventoryService, timeout(10_000))
        .provisionWithQuantity(eq(productId), any(), any(), any());
    verify(inventoryService, never()).removeIfPresent(eq(productId));
  }

  @Test
  @DisplayName(
      "PRODUCT_CREATED, when provisionWithQuantity fails, publishes"
          + " INVENTORY_PROVISIONING_FAILED - the choreographed creation-saga's compensation"
          + " trigger, consumed from a real broker")
  void productCreatedCompensatesOnProvisioningFailure() throws Exception {
    UUID productId = UUID.randomUUID();
    doThrow(new RuntimeException("connection reset"))
        .when(inventoryService)
        .provisionWithQuantity(eq(productId), any(), any(), any());

    publish(ProductEventType.PRODUCT_CREATED, productId);

    verify(inventoryEvents, timeout(10_000))
        .publishNow(
            eq(InventoryEventType.INVENTORY_PROVISIONING_FAILED),
            eq(productId),
            any(),
            eq("connection reset"));
  }

  @Test
  @DisplayName("PRODUCT_DELETED, consumed from a real broker, removes inventory")
  void productDeletedIsRemoved() throws Exception {
    UUID productId = UUID.randomUUID();
    publish(ProductEventType.PRODUCT_DELETED, productId);

    verify(inventoryService, timeout(10_000)).removeIfPresent(eq(productId));
    verify(inventoryService, never()).provisionWithQuantity(eq(productId), any(), any(), any());
  }

  @Test
  @DisplayName(
      "PRODUCT_UPDATED, consumed from a real broker, denormalizes the product snapshot - now"
          + " load-bearing (Phase 2 Open Question #4, closed)")
  void productUpdatedIsDenormalized() throws Exception {
    UUID productId = UUID.randomUUID();
    ProductEvent event =
        new ProductEvent(
            UUID.randomUUID(),
            ProductEventType.PRODUCT_UPDATED,
            productId,
            Instant.now(),
            new ProductSnapshot("Widget", "SKU-01"),
            null);
    String json = OBJECT_MAPPER.writeValueAsString(event);
    producer.send(new ProducerRecord<>("product.events", productId.toString(), json)).get();

    verify(inventoryService, timeout(10_000))
        .denormalizeProductSnapshot(productId, "Widget", "SKU-01");
    verify(inventoryService, never()).provisionWithQuantity(eq(productId), any(), any(), any());
    verify(inventoryService, never()).removeIfPresent(eq(productId));
  }

  private static void publish(ProductEventType type, UUID productId) throws Exception {
    ProductEvent event =
        new ProductEvent(UUID.randomUUID(), type, productId, Instant.now(), null, null);
    String json = OBJECT_MAPPER.writeValueAsString(event);
    producer.send(new ProducerRecord<>("product.events", productId.toString(), json)).get();
  }
}
