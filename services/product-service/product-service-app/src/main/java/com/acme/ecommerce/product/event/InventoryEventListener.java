package com.acme.ecommerce.product.event;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.product.service.ProductCatalogService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes inventory events from inventory.events Kafka topic.
 *
 * <p>Updates product-service's denormalized inventory field (last_known_quantity) when stock levels
 * change, and deletes the product on INVENTORY_PROVISIONING_FAILED (the creation saga's
 * compensation). Deliberately NEVER publishes a reply event - no ping-pong.
 *
 * <p>Idempotent via product table's primary key and version field - replayed events safely update
 * the denormalized field to the same value.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventListener {

  private final ProductCatalogService productCatalog;

  /**
   * Consumes inventory events and updates product denormalization.
   *
   * <p>Called whenever stock levels change in inventory-service. Updates
   * product.products.last_known_quantity as a non-authoritative snapshot for display.
   *
   * @param event the inventory event from Kafka
   */
  @KafkaListener(
      topics = "inventory.events",
      groupId = "product-service-inventory-listener",
      containerFactory = "kafkaListenerContainerFactory")
  public void onInventoryEvent(InventoryEvent event) {
    UUID productId = event.getProductId();

    try {
      switch (event.getEventType()) {
        case INVENTORY_QUANTITY_CHANGED -> {
          log.info(
              "Received INVENTORY_QUANTITY_CHANGED event productId={} quantity={}",
              productId,
              event.getQuantity());
          productCatalog.applyInventorySignal(productId, event.getQuantity());
        }
        case INVENTORY_LOW_STOCK ->
            log.info(
                "Received INVENTORY_LOW_STOCK event productId={} quantity={}",
                productId,
                event.getQuantity());
        case INVENTORY_PROVISIONING_FAILED -> {
          log.warn(
              "Inventory provisioning failed productId={} reason={}; compensating with delete",
              productId,
              event.getReason());
          productCatalog.delete(productId);
        }
      }
    } catch (ResourceNotFoundException e) {
      // Graceful: product may have been deleted before event was processed
      log.debug(
          "Product not found during inventory update productId={}: {}", productId, e.getMessage());
    } catch (Exception e) {
      log.error("Error processing inventory event productId={}: {}", productId, e.getMessage(), e);
    }
  }
}
