package com.acme.ecommerce.product.event;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.product.service.ProductCatalogService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes rating events from rating.events Kafka topic.
 *
 * <p>Updates product-service's denormalized rating fields (average_rating, rating_count,
 * rating_distribution) when a rating is added/updated/deleted. Deliberately NEVER publishes a reply
 * event - no ping-pong.
 *
 * <p>Idempotent via product table's primary key and version field - a replayed event will either
 * find no product (if it was deleted) and silently return, or update the same product again with
 * the same calculated averages.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RatingEventListener {

  private final ProductCatalogService productCatalog;

  /**
   * Consumes rating events and updates product denormalization.
   *
   * <p>Called whenever a rating is added, updated, or deleted in the rating-service. Synchronizes
   * product.products.average_rating, rating_count, and rating_distribution from the ratings
   * aggregate.
   *
   * @param event the rating event from Kafka
   */
  @KafkaListener(
      topics = "rating.events",
      groupId = "product-service-rating-listener",
      containerFactory = "kafkaListenerContainerFactory")
  public void onRatingEvent(RatingEvent event) {
    UUID productId = event.productId();

    try {
      log.info(
          "Received rating event eventType={} productId={} ratingId={}",
          event.eventType(),
          productId,
          event.ratingId());

      // Call product catalog to update denormalized rating fields.
      // In production, this would fetch aggregates from rating-service via Feign client
      // and update the product's average_rating, rating_count, and rating_distribution.
      // For now, this is a placeholder for the denormalization update.
      productCatalog.updateRatingDenormalization(productId);

      log.info(
          "Successfully updated product rating denormalization productId={} eventType={}",
          productId,
          event.eventType());
    } catch (ResourceNotFoundException e) {
      // Graceful: product may have been deleted before event was processed
      log.debug(
          "Product not found during rating update productId={}: {}", productId, e.getMessage());
    } catch (Exception e) {
      log.error("Error processing rating event productId={}: {}", productId, e.getMessage(), e);
    }
  }
}
