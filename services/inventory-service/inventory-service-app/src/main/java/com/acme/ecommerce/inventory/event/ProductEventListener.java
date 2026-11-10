package com.acme.ecommerce.inventory.event;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.inventory.service.InventoryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes product events from product.events Kafka topic.
 *
 * Updates inventory-service's denormalized product fields (product_name, product_sku)
 * and provisions inventory for newly created products. Deliberately NEVER publishes
 * a reply event - no ping-pong.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventListener {

  private final InventoryService inventory;

  /**
   * Consumes product events and updates inventory denormalization.
   */
  @KafkaListener(
      topics = "product.events",
      groupId = "inventory-service-product-listener",
      containerFactory = "kafkaListenerContainerFactory")
  public void onProductEvent(ProductEvent event) {
    UUID productId = event.productId();
    
    try {
      switch (event.eventType()) {
        case PRODUCT_CREATED:
          log.info(
              "Received PRODUCT_CREATED event productId={} initialQuantity={}",
              productId,
              event.initialQuantity());
          if (event.payload() != null) {
            inventory.provisionWithQuantity(
                productId,
                event.initialQuantity(),
                event.payload().name(),
                event.payload().sku());
          }
          break;
          
        case PRODUCT_UPDATED:
          log.info("Received PRODUCT_UPDATED event productId={}", productId);
          if (event.payload() != null) {
            inventory.denormalizeProductSnapshot(
                productId, event.payload().name(), event.payload().sku());
          }
          break;
          
        case PRODUCT_DELETED:
          log.info("Received PRODUCT_DELETED event productId={}", productId);
          inventory.removeIfPresent(productId);
          break;
      }
      
      log.info(
          "Successfully processed product event eventType={} productId={}",
          event.eventType(),
          productId);
    } catch (ResourceNotFoundException e) {
      log.debug(
          "Product not found during event processing eventType={} productId={}: {}",
          event.eventType(),
          productId,
          e.getMessage());
    } catch (Exception e) {
      log.error(
          "Error processing product event eventType={} productId={}: {}",
          event.eventType(),
          productId,
          e.getMessage(),
          e);
    }
  }
}
