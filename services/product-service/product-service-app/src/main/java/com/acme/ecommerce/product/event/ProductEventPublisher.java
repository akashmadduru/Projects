package com.acme.ecommerce.product.event;

import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.product.api.dto.ProductResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes {@link ProductEvent}s to {@code product.events}, strictly AFTER the enclosing
 * {@code @Transactional} method's transaction commits - never inside it (HLD S5a, the #1 risk this
 * feature plan flags).
 *
 * <p>USES {@code TransactionSynchronizationManager}, NOT AN {@code @TransactionalEventListener}, so
 * the "after commit, never inside" rule is explicit and inspectable in one class rather than
 * implied by an annotation's phase attribute. The registered {@link TransactionSynchronization}
 * fires its {@code afterCommit()} callback only once the Postgres transaction has actually
 * committed; if the transaction rolls back, {@code afterCommit()} never runs and nothing is
 * published.
 *
 * <p>{@code afterCompletion(int status)} IS DELIBERATELY NOT OVERRIDDEN. The default no-op is
 * exactly correct for the {@code STATUS_ROLLED_BACK} case: a rolled-back write must never publish,
 * and doing nothing is how that is guaranteed rather than merely intended.
 *
 * <p>GUARDED AGAINST MISUSE. If this method is ever called outside an active transaction, there is
 * no commit to hang the publish off of - failing loudly with {@link IllegalStateException} is
 * preferred over silently publishing an event for a write that may never happen.
 *
 * <p>A {@code send()} FAILURE IS LOGGED, NEVER RETHROWN (HLD S7/S10). By the time {@code
 * afterCommit()} runs, the database write has already committed; for an HTTP-triggered write this
 * callback runs synchronously on the request thread before the controller returns, so an uncaught
 * exception here - including one thrown synchronously by {@code send()} itself while resolving
 * cluster metadata, not only via its returned future's callback - would turn a successful write
 * into a 500 the caller never asked for. A Kafka outage must delay the event, never the response.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventPublisher {

  private static final String TOPIC = "product.events";

  private final KafkaTemplate<String, ProductEvent> kafka;
  private final TimeProvider timeProvider;

  /**
   * Publishes an event carrying the product's current state.
   *
   * @param type which lifecycle transition occurred
   * @param product the product snapshot to publish
   */
  public void publishAfterCommit(ProductEventType type, ProductResponse product) {
    publishAfterCommit(type, product.getId(), product, null);
  }

  /**
   * Publishes a {@link ProductEventType#PRODUCT_CREATED} event carrying the product's current state
   * and the caller-requested starting stock quantity (docs/HLD-catalog-enrichment-and-saga.md S2,
   * docs/LLD-catalog-enrichment-and-saga.md S2.1) - the choreographed creation-saga's entry point
   * onto {@code product.events}.
   *
   * @param type which lifecycle transition occurred (only {@code PRODUCT_CREATED} carries an {@code
   *     initialQuantity} in this pass, but the parameter is not restricted to it)
   * @param product the product snapshot to publish
   * @param initialQuantity the caller-requested starting stock quantity, or {@code null}
   */
  public void publishAfterCommit(
      ProductEventType type, ProductResponse product, Integer initialQuantity) {
    publishAfterCommit(type, product.getId(), product, initialQuantity);
  }

  /**
   * Publishes an event, with an explicit {@code productId} and an optional payload - used for
   * {@link ProductEventType#PRODUCT_DELETED}, which has no response body to send.
   *
   * @param type which lifecycle transition occurred
   * @param productId the product the event concerns
   * @param payloadOrNull the product snapshot, or {@code null} for a delete
   * @throws IllegalStateException if called outside an active transaction
   */
  public void publishAfterCommit(
      ProductEventType type, UUID productId, ProductResponse payloadOrNull) {
    publishAfterCommit(type, productId, payloadOrNull, null);
  }

  private void publishAfterCommit(
      ProductEventType type,
      UUID productId,
      ProductResponse payloadOrNull,
      Integer initialQuantity) {

    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("publishAfterCommit called outside an active transaction");
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            ProductEvent event =
                ProductEvent.builder()
                    .eventId(UUID.randomUUID())
                    .eventType(type)
                    .productId(productId)
                    .publishedAt(timeProvider.now())
                    .payload(payloadOrNull)
                    .initialQuantity(initialQuantity)
                    .build();
            log.info(
                "Publishing product event eventId={} eventType={} productId={}",
                event.getEventId(),
                event.getEventType(),
                event.getProductId());
            try {
              // send() itself can throw SYNCHRONOUSLY (not just via its returned
              // future's callback) - e.g. a metadata-fetch timeout when no broker
              // is reachable. By the time this runs, the write has already
              // committed and, for an HTTP-triggered write, the controller is
              // about to return - an uncaught exception here would turn a
              // successful write into a 500 the caller never asked for. HLD S7 is
              // explicit that a Kafka outage must not surface as an HTTP error,
              // so this is caught and logged, never rethrown.
              kafka.send(TOPIC, productId.toString(), event);
            } catch (RuntimeException e) {
              log.warn(
                  "Failed to publish product event eventId={} eventType={} productId={}: {}",
                  event.getEventId(),
                  event.getEventType(),
                  event.getProductId(),
                  e.getMessage());
            }
          }
          // afterCompletion(int status) intentionally NOT overridden for
          // ROLLED_BACK - the default no-op is exactly correct: a rolled-back
          // write must never publish, and doing nothing is how that is
          // guaranteed rather than merely intended.
        });
  }
}
