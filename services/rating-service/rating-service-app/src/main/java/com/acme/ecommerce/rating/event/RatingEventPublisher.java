package com.acme.ecommerce.rating.event;

import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.rating.api.dto.RatingResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes {@link RatingEvent}s to {@code rating.events} Kafka topic, strictly AFTER the enclosing
 * {@code @Transactional} method's transaction commits - never inside it.
 *
 * <p>USES {@code TransactionSynchronizationManager}, not an {@code @TransactionalEventListener}, so
 * the "after commit, never inside" rule is explicit and inspectable in one class.
 *
 * <p>A {@code send()} FAILURE IS LOGGED, NEVER RETHROWN (HLD S7/S10). By the time {@code
 * afterCommit()} runs, the database write has already committed; an uncaught exception here would
 * turn a successful write into a 500 the caller never asked for. A Kafka outage must delay the
 * event, never the response.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RatingEventPublisher {

  private static final String TOPIC = "rating.events";

  private final KafkaTemplate<String, RatingEvent> kafka;
  private final TimeProvider timeProvider;

  /**
   * Publishes a rating event carrying the rating's current state.
   *
   * @param type which lifecycle transition occurred
   * @param ratingId the rating identifier
   * @param productId the product being rated
   * @param payload the rating snapshot (or null for delete)
   */
  public void publishAfterCommit(
      RatingEventType type, UUID ratingId, UUID productId, RatingResponse payload) {

    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("publishAfterCommit called outside an active transaction");
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            RatingEvent event =
                new RatingEvent(
                    UUID.randomUUID(), type, ratingId, productId, timeProvider.now(), payload);
            log.info(
                "Publishing rating event eventId={} eventType={} ratingId={} productId={}",
                event.getEventId(),
                event.getEventType(),
                event.getRatingId(),
                event.getProductId());
            try {
              kafka.send(TOPIC, ratingId.toString(), event);
            } catch (RuntimeException e) {
              log.warn(
                  "Failed to publish rating event eventId={} eventType={} ratingId={}: {}",
                  event.getEventId(),
                  event.getEventType(),
                  event.getRatingId(),
                  e.getMessage());
            }
          }
        });
  }
}
