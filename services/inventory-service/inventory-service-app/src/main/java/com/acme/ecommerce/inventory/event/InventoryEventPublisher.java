package com.acme.ecommerce.inventory.event;

import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.inventory.domain.Inventory;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes {@link InventoryEvent}s to {@code inventory.events}, strictly AFTER the enclosing
 * {@code @Transactional} method's transaction commits - never inside it. Structurally identical to
 * product-service's {@code ProductEventPublisher}
 * (docs/architecture/design/LLD-catalog-enrichment-and-saga.md S2.2 calls this out as "a mechanical
 * mirror of the existing, already-reviewed class") - every invariant documented there holds here
 * unchanged:
 *
 * <p>USES {@code TransactionSynchronizationManager}, NOT AN {@code @TransactionalEventListener}, so
 * the "after commit, never inside" rule is explicit and inspectable in one class rather than
 * implied by an annotation's phase attribute.
 *
 * <p>{@code afterCompletion(int status)} IS DELIBERATELY NOT OVERRIDDEN - the default no-op is
 * exactly correct for the {@code STATUS_ROLLED_BACK} case.
 *
 * <p>GUARDED AGAINST MISUSE - calling this outside an active transaction fails loudly with {@link
 * IllegalStateException} rather than silently publishing an event for a write that may never
 * happen.
 *
 * <p>A {@code send()} FAILURE IS LOGGED, NEVER RETHROWN - a Kafka outage must delay the event,
 * never turn a successful write into an HTTP error.
 */
@Component
public class InventoryEventPublisher {

  private static final String TOPIC = "inventory.events";
  private static final Logger log = LoggerFactory.getLogger(InventoryEventPublisher.class);

  private final KafkaTemplate<String, InventoryEvent> kafka;
  private final TimeProvider timeProvider;

  public InventoryEventPublisher(
      KafkaTemplate<String, InventoryEvent> kafka, TimeProvider timeProvider) {
    this.kafka = kafka;
    this.timeProvider = timeProvider;
  }

  /**
   * Publishes an event carrying the row's current quantity.
   *
   * @param type which inventory-side change occurred
   * @param row the stock-level row to publish, post-write
   */
  public void publishAfterCommit(InventoryEventType type, Inventory row) {
    publishAfterCommit(type, row.getProductId(), row.getQuantity());
  }

  /**
   * Publishes an event with an explicit {@code productId}/{@code newQuantity}.
   *
   * @param type which inventory-side change occurred
   * @param productId the product the event concerns
   * @param newQuantity the quantity on hand at publish time
   * @throws IllegalStateException if called outside an active transaction
   */
  public void publishAfterCommit(InventoryEventType type, UUID productId, Integer newQuantity) {
    publishAfterCommit(type, productId, null, newQuantity);
  }

  public void publishAfterCommit(
      InventoryEventType type, UUID productId, UUID variantId, Integer newQuantity) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("publishAfterCommit called outside an active transaction");
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            InventoryEvent event =
                new InventoryEvent(
                    UUID.randomUUID(),
                    type,
                    productId,
                    timeProvider.now(),
                    newQuantity,
                    null,
                    null,
                    variantId);
            send(event);
          }
          // afterCompletion(int status) intentionally NOT overridden for
          // ROLLED_BACK - see ProductEventPublisher's identical comment.
        });
  }

  /**
   * Publishes an {@link InventoryEventType#INVENTORY_PROVISIONING_FAILED} event IMMEDIATELY,
   * best-effort - NOT gated on a transaction commit via {@link TransactionSynchronizationManager}
   * like {@link #publishAfterCommit}. Used exactly once in this design, from inside {@code
   * ProductEventListener}'s catch block for a failed {@code provisionWithQuantity} call
   * (docs/architecture/design/LLD-catalog-enrichment-and-saga.md S2.2): the persistence attempt
   * that failed never got far enough to have an open transaction, or one that already rolled back,
   * so there is no commit left to hang an {@code afterCommit} callback off of, and nothing left to
   * protect against a race with by waiting for one.
   *
   * @param type which inventory-side change occurred (only {@code INVENTORY_PROVISIONING_FAILED}
   *     uses this method in this pass, but the parameter is not restricted to it)
   * @param productId the product whose provisioning failed
   * @param requestedQuantity the caller-requested starting quantity that failed to provision
   * @param reason a short, human-readable description of the failure
   */
  public void publishNow(
      InventoryEventType type, UUID productId, Integer requestedQuantity, String reason) {
    InventoryEvent event =
        new InventoryEvent(
            UUID.randomUUID(),
            type,
            productId,
            timeProvider.now(),
            null,
            requestedQuantity,
            reason,
            null);
    send(event);
  }

  private void send(InventoryEvent event) {
    log.info(
        "Publishing inventory event eventId={} eventType={} productId={}",
        event.getEventId(),
        event.getEventType(),
        event.getProductId());
    try {
      // send() itself can throw SYNCHRONOUSLY - see ProductEventPublisher's
      // identical comment. By the time this runs, the write has already
      // committed (or, for publishNow, has already failed/rolled back), so an
      // uncaught exception here would turn either case into an unhandled error
      // on a thread this event's own caller does not expect one from.
      kafka.send(TOPIC, event.getProductId().toString(), event);
    } catch (RuntimeException e) {
      log.warn(
          "Failed to publish inventory event eventId={} eventType={} productId={}: {}",
          event.getEventId(),
          event.getEventType(),
          event.getProductId(),
          e.getMessage());
    }
  }
}
