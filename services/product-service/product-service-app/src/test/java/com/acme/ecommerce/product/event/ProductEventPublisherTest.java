package com.acme.ecommerce.product.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.time.FixedTimeProvider;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * Unit tests for the "#1 risk" this feature plan flags (HLD S5a, LLD S9): a rolled-back write must
 * NEVER publish to Kafka, and a committed write must publish only after that commit, never inside
 * the transaction it belongs to.
 *
 * <p>No Spring context and no real transaction manager - {@link TransactionSynchronizationManager}
 * exposes exactly the seam this class needs: synchronization can be activated, a synchronization
 * registered, and the platform's own {@link TransactionSynchronizationUtils} can simulate the
 * commit/rollback callback Spring's transaction infrastructure would otherwise fire, all without a
 * database.
 */
@ExtendWith(MockitoExtension.class)
class ProductEventPublisherTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Mock private KafkaTemplate<String, ProductEvent> kafka;

  // Built in @BeforeEach, NOT as a field initializer: a field initializer runs during instance
  // construction, before MockitoExtension injects @Mock fields, so `kafka` would still be null at
  // that point and the publisher would be built holding a null KafkaTemplate.
  private ProductEventPublisher publisher;

  @BeforeEach
  void setUp() {
    publisher = new ProductEventPublisher(kafka, new FixedTimeProvider(NOW));
  }

  @AfterEach
  void clearSynchronization() {
    // Defensive: a test that fails before reaching its own cleanup must not leak
    // an active synchronization into the next test in the class.
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("calling outside an active transaction fails loudly rather than silently publishing")
  void throwsWhenNoTransactionIsActive() {
    ProductResponse product = sampleProduct();

    assertThatThrownBy(
            () -> publisher.publishAfterCommit(ProductEventType.PRODUCT_CREATED, product))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(kafka);
  }

  @Test
  @DisplayName("a committed transaction publishes to Kafka, keyed by productId, only after commit")
  void publishesAfterCommit() {
    TransactionSynchronizationManager.initSynchronization();
    ProductResponse product = sampleProduct();

    publisher.publishAfterCommit(ProductEventType.PRODUCT_CREATED, product);

    // Nothing sent yet - the publish must not happen inside the transaction,
    // only once the (simulated) commit below fires afterCommit().
    verifyNoInteractions(kafka);

    triggerCommitThenClear();

    ArgumentCaptor<ProductEvent> captor = ArgumentCaptor.forClass(ProductEvent.class);
    verify(kafka).send(eq("product.events"), eq(product.getId().toString()), captor.capture());
    ProductEvent event = captor.getValue();
    assertThat(event.getEventId()).isNotNull();
    assertThat(event.getEventType()).isEqualTo(ProductEventType.PRODUCT_CREATED);
    assertThat(event.getProductId()).isEqualTo(product.getId());
    assertThat(event.getPublishedAt()).isEqualTo(NOW);
    assertThat(event.getPayload()).isEqualTo(product);
  }

  @Test
  @DisplayName("a rolled-back transaction never sends to Kafka - the default afterCompletion no-op")
  void doesNotPublishOnRollback() {
    TransactionSynchronizationManager.initSynchronization();
    ProductResponse product = sampleProduct();

    publisher.publishAfterCommit(ProductEventType.PRODUCT_CREATED, product);

    triggerRollbackThenClear();

    verify(kafka, never()).send(any(), any(), any());
  }

  @Test
  @DisplayName("the delete overload publishes with a null payload, keyed by the explicit productId")
  void publishesDeleteWithNullPayload() {
    TransactionSynchronizationManager.initSynchronization();
    UUID productId = UUID.randomUUID();

    publisher.publishAfterCommit(ProductEventType.PRODUCT_DELETED, productId, null);
    triggerCommitThenClear();

    ArgumentCaptor<ProductEvent> captor = ArgumentCaptor.forClass(ProductEvent.class);
    verify(kafka).send(eq("product.events"), eq(productId.toString()), captor.capture());
    ProductEvent event = captor.getValue();
    assertThat(event.getEventType()).isEqualTo(ProductEventType.PRODUCT_DELETED);
    assertThat(event.getProductId()).isEqualTo(productId);
    assertThat(event.getPayload()).isNull();
  }

  @Test
  @DisplayName(
      "a synchronous send() failure is logged, not rethrown - a Kafka outage must not turn a"
          + " committed write into an HTTP error")
  void sendFailureIsSwallowed() {
    TransactionSynchronizationManager.initSynchronization();
    ProductResponse product = sampleProduct();
    // send() can throw SYNCHRONOUSLY - e.g. TimeoutException wrapped in a
    // KafkaException while resolving cluster metadata - not only via its
    // returned future's callback. This is exactly what happens against an
    // unreachable broker, and it must not escape afterCommit().
    when(kafka.send(any(), any(), any()))
        .thenThrow(new org.springframework.kafka.KafkaException("no broker available"));

    publisher.publishAfterCommit(ProductEventType.PRODUCT_CREATED, product);

    // Must not throw - the whole point of this test.
    triggerCommitThenClear();

    verify(kafka).send(eq("product.events"), eq(product.getId().toString()), any());
  }

  /**
   * Simulates what Spring's transaction infrastructure does at commit: {@code afterCommit()} on
   * every registered synchronization, then {@code afterCompletion(STATUS_COMMITTED)}.
   */
  private static void triggerCommitThenClear() {
    for (TransactionSynchronization synchronization :
        TransactionSynchronizationManager.getSynchronizations()) {
      synchronization.afterCommit();
    }
    TransactionSynchronizationUtils.triggerAfterCompletion(
        TransactionSynchronization.STATUS_COMMITTED);
    TransactionSynchronizationManager.clearSynchronization();
  }

  /**
   * Simulates a rollback: {@code afterCommit()} is deliberately NOT invoked - a rollback never
   * reaches it. Only {@code afterCompletion(ROLLED_BACK)} fires, and {@link ProductEventPublisher}
   * does not override it, so the default no-op is exactly what this proves.
   */
  private static void triggerRollbackThenClear() {
    TransactionSynchronizationUtils.triggerAfterCompletion(
        TransactionSynchronization.STATUS_ROLLED_BACK);
    TransactionSynchronizationManager.clearSynchronization();
  }

  private static ProductResponse sampleProduct() {
    return ProductResponse.builder()
        .id(UUID.randomUUID())
        .sku("SKU-01")
        .name("Widget")
        .priceAmount(new BigDecimal("9.99"))
        .priceCurrency("USD")
        .active(true)
        .version(0L)
        .images(List.of())
        .build();
  }
}
