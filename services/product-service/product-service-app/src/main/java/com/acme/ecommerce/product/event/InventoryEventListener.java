package com.acme.ecommerce.product.event;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.product.event.InventoryEvent;
import com.acme.ecommerce.product.service.ProductCatalogService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes inventory events from inventory.events Kafka topic.
 *
 * Updates product-service's denormalized inventory field (last_known_quantity)
 * when stock levels change. Deliberately NEVER publishes a reply event - no ping-pong.
 *
 * Idempotent via product table's primary key and version field - replayed events
 * safely update the denormalized field to the same value.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventListener {

  private final ProductCatalogService productCatalog;

  /**
   * Consumes inventory events and updates product denormalization.
   *
   * Called whenever stock levels change in inventory-service.
   * Updates product.products.last_known_quantity as a non-authoritative snapshot for display.
   *
   * @param event the inventory event from Kafka
   */
  @KafkaListener(
      topics = "inventory.events",
      groupId = "product-service-inventory-listener",
      containerFactory = "kafkaListenerContainerFactory")
  public void onInventoryEvent(InventoryEvent event) {
    UUID productId = event.productId();
    
    try {
      log.info(
          "Received INVENTORY_QUANTITY_CHANGED event productId={} quantity={}",
          productId,
          event.quantity());
      
      // Apply denormalized inventory signal to product
      productCatalog.applyInventorySignal(productId, event.quantity());
      
      log.info(
          "Successfully updated product denormalization productId={} quantity={}",
          productId,
          event.quantity());
    } catch (ResourceNotFoundException e) {
      // Graceful: product may have been deleted before event was processed
      log.debug(
          "Product not found during inventory update productId={}: {}",
          productId,
          e.getMessage());
    } catch (Exception e) {
      log.error(
          "Error processing inventory event productId={}: {}",
          productId,
          e.getMessage(),
          e);
    }
  }
}
