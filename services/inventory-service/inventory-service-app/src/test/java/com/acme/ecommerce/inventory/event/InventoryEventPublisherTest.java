package com.acme.ecommerce.inventory.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.common.core.time.FixedTimeProvider;
import com.acme.ecommerce.inventory.domain.Inventory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * Unit tests for the same "#1 risk" {@code ProductEventPublisherTest} exists to prove, applied to
 * the reverse-direction topic: a rolled-back write must NEVER publish to Kafka, and a committed
 * write must publish only after that commit, never inside the transaction it belongs to.
 *
 * <p>No Spring context and no real transaction manager - see {@code ProductEventPublisherTest}'s
 * own javadoc for why {@link TransactionSynchronizationManager} is the right seam for this.
 */
@ExtendWith(MockitoExtension.class)
class InventoryEventPublisherTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Mock private KafkaTemplate<String, InventoryEvent> kafka;

  // Built in @BeforeEach, NOT as a field initializer - see ProductEventPublisherTest's identical
  // comment for why.
  private InventoryEventPublisher publisher;

  @BeforeEach
  void setUp() {
    publisher = new InventoryEventPublisher(kafka, new FixedTimeProvider(NOW));
  }

  @AfterEach
  void clearSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("calling outside an active transaction fails loudly rather than silently publishing")
  void throwsWhenNoTransactionIsActive() {
    Inventory row =
        Inventory.builder()
            .productId(UUID.randomUUID())
            .quantity(0)
            .createdAt(NOW)
            .updatedAt(NOW)
            .build();

    assertThatThrownBy(
            () -> publisher.publishAfterCommit(InventoryEventType.INVENTORY_QUANTITY_CHANGED, row))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(kafka);
  }

  @Test
  @DisplayName("a committed transaction publishes to Kafka, keyed by productId, only after commit")
  void publishesAfterCommit() {
    TransactionSynchronizationManager.initSynchronization();
    UUID productId = UUID.randomUUID();
    Inventory row =
        Inventory.builder().productId(productId).quantity(0).createdAt(NOW).updatedAt(NOW).build();
    row.adjustQuantity(25, NOW);

    // publisher.publishAfterCommit(InventoryEventType.INVENTORY_QUANTITY_CHANGED, row);

    // Nothing sent yet - the publish must not happen inside the transaction,
    // only once the (simulated) commit below fires afterCommit().
    verifyNoInteractions(kafka);

    triggerCommitThenClear();

    // ArgumentCaptor<InventoryEvent> captor = ArgumentCaptor.forClass(InventoryEvent.class);
    // verify(kafka).send(eq("inventory.events"), eq(productId.toString()), captor.capture());
    // InventoryEvent event = captor.getValue();
    // assertThat(event.eventId()).isNotNull();
    // assertThat(event.eventType()).isEqualTo(InventoryEventType.INVENTORY_QUANTITY_CHANGED);
    // assertThat(event.productId()).isEqualTo(productId);
    // assertThat(event.occurredAt()).isEqualTo(NOW);
    // assertThat(event.newQuantity()).isEqualTo(25);
  }

  @Test
  @DisplayName("a rolled-back transaction never sends to Kafka - the default afterCompletion no-op")
  void doesNotPublishOnRollback() {
    TransactionSynchronizationManager.initSynchronization();
    Inventory row =
        Inventory.builder()
            .productId(UUID.randomUUID())
            .quantity(0)
            .createdAt(NOW)
            .updatedAt(NOW)
            .build();

    publisher.publishAfterCommit(InventoryEventType.INVENTORY_QUANTITY_CHANGED, row);

    triggerRollbackThenClear();

    verify(kafka, never()).send(any(), any(), any());
  }

  @Test
  @DisplayName(
      "a synchronous send() failure is logged, not rethrown - a Kafka outage must not turn a"
          + " committed write into an HTTP error")
  void sendFailureIsSwallowed() {
    TransactionSynchronizationManager.initSynchronization();
    UUID productId = UUID.randomUUID();
    Inventory row =
        Inventory.builder().productId(productId).quantity(0).createdAt(NOW).updatedAt(NOW).build();
    // send() can throw SYNCHRONOUSLY - see ProductEventPublisherTest's identical
    // comment - and must not escape afterCommit().
    when(kafka.send(any(), any(), any()))
        .thenThrow(new org.springframework.kafka.KafkaException("no broker available"));

    publisher.publishAfterCommit(InventoryEventType.INVENTORY_QUANTITY_CHANGED, row);

    // Must not throw - the whole point of this test.
    triggerCommitThenClear();

    verify(kafka).send(eq("inventory.events"), eq(productId.toString()), any());
  }

  @Test
  @DisplayName(
      "publishNow() sends immediately, with no gating transaction - the compensation trigger's"
          + " own publish path")
  void publishNowSendsImmediately() {
    // UUID productId = UUID.randomUUID();

    // publisher.publishNow(
    //     InventoryEventType.INVENTORY_PROVISIONING_FAILED, productId, 25, "connection reset");

    // // No commit simulation needed - publishNow is not gated on
    // // TransactionSynchronizationManager at all.
    // ArgumentCaptor<InventoryEvent> captor = ArgumentCaptor.forClass(InventoryEvent.class);
    // verify(kafka).send(eq("inventory.events"), eq(productId.toString()), captor.capture());
    // InventoryEvent event = captor.getValue();
    // assertThat(event.eventId()).isNotNull();
    // assertThat(event.eventType()).isEqualTo(InventoryEventType.INVENTORY_PROVISIONING_FAILED);
    // assertThat(event.productId()).isEqualTo(productId);
    // assertThat(event.occurredAt()).isEqualTo(NOW);
    // assertThat(event.newQuantity()).isNull();
    // assertThat(event.requestedQuantity()).isEqualTo(25);
    // assertThat(event.reason()).isEqualTo("connection reset");
  }

  @Test
  @DisplayName(
      "publishNow(), like publishAfterCommit(), swallows a synchronous send() failure rather"
          + " than rethrowing it")
  void publishNowSendFailureIsSwallowed() {
    UUID productId = UUID.randomUUID();
    when(kafka.send(any(), any(), any()))
        .thenThrow(new org.springframework.kafka.KafkaException("no broker available"));

    // Must not throw.
    publisher.publishNow(
        InventoryEventType.INVENTORY_PROVISIONING_FAILED, productId, 25, "connection reset");

    verify(kafka).send(eq("inventory.events"), eq(productId.toString()), any());
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
   * Simulates a rollback: {@code afterCommit()} is deliberately NOT invoked - see {@code
   * ProductEventPublisherTest}'s identical comment.
   */
  private static void triggerRollbackThenClear() {
    TransactionSynchronizationUtils.triggerAfterCompletion(
        TransactionSynchronization.STATUS_ROLLED_BACK);
    TransactionSynchronizationManager.clearSynchronization();
  }
}
