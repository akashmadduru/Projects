package com.acme.ecommerce.product.event;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.acme.ecommerce.product.service.ProductCatalogService;
import com.acme.ecommerce.product.support.AbstractProductIntegrationTest;
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
 * Proves {@link InventoryEventListener} dispatches each of the two currently-wired event types
 * correctly against a REAL broker, not a mock of {@code KafkaTemplate}/{@code @KafkaListener} - a
 * real {@code DefaultKafkaConsumerFactory}, real deserialization (including {@code
 * spring.json.trusted.packages}), and a real listener container all participate. Mirrors
 * inventory-service's {@code ProductEventListenerIntegrationTest} exactly.
 *
 * <p>{@link ProductCatalogService} IS STILL MOCKED. This is deliberately an integration test of the
 * listener's wiring and dispatch, not a re-test of {@link ProductCatalogService}'s own idempotency
 * decisions - those are {@code ProductCatalogServiceTest}'s job.
 */
@Testcontainers
class InventoryEventListenerIntegrationTest extends AbstractProductIntegrationTest {

  // See ProductEventListenerIntegrationTest's identical comment on why
  // ConfluentKafkaContainer, not the apache/kafka image infra/docker-compose.yml
  // runs.
  @Container
  static final ConfluentKafkaContainer KAFKA =
      new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

  @MockitoBean private ProductCatalogService catalog;

  private static KafkaProducer<String, String> producer;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  @BeforeAll
  static void startProducer() {
    // A plain String producer, not the application's own producer configuration
    // - the wire format is JSON bytes either way, and this keeps the test
    // independent of any Spring-managed producer bean this service does not
    // otherwise have for inventory.events.
    Map<String, Object> producerProps = KafkaTestUtils.producerProps(KAFKA.getBootstrapServers());
    // KafkaTestUtils.producerProps() defaults key.serializer to IntegerSerializer
    // - this producer sends the productId as a String key, so both serializers
    // must be overridden.
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
  @DisplayName("INVENTORY_QUANTITY_CHANGED, consumed from a real broker, applies the signal")
  void quantityChangedIsApplied() throws Exception {
    UUID productId = UUID.randomUUID();
    publish(InventoryEventType.INVENTORY_QUANTITY_CHANGED, productId, 42);

    verify(catalog, timeout(10_000)).applyInventorySignal(eq(productId), eq(42));
  }

  @Test
  @DisplayName(
      "INVENTORY_PROVISIONING_FAILED, consumed from a real broker, compensates via the existing"
          + " delete(productId) path")
  void provisioningFailedCompensatesWithDelete() throws Exception {
    UUID productId = UUID.randomUUID();
    InventoryEvent event =
        new InventoryEvent(
            UUID.randomUUID(),
            InventoryEventType.INVENTORY_PROVISIONING_FAILED,
            productId,
            Instant.now(),
            null,
            25,
            "connection reset");
    String json = OBJECT_MAPPER.writeValueAsString(event);
    producer.send(new ProducerRecord<>("inventory.events", productId.toString(), json)).get();

    verify(catalog, timeout(10_000)).delete(productId);
  }

  private static void publish(InventoryEventType type, UUID productId, Integer newQuantity)
      throws Exception {
    InventoryEvent event =
        new InventoryEvent(
            UUID.randomUUID(), type, productId, Instant.now(), newQuantity, null, null);
    String json = OBJECT_MAPPER.writeValueAsString(event);
    producer.send(new ProducerRecord<>("inventory.events", productId.toString(), json)).get();
  }
}
