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
 * <p>Updates inventory-service's denormalized product fields (product_name, product_sku) and
 * provisions inventory for newly created products. The only event it ever publishes is the creation
 * saga's compensation trigger, INVENTORY_PROVISIONING_FAILED - never a reply to an ordinary update,
 * so there is no ping-pong.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventListener {

  private final InventoryService inventory;
  private final InventoryEventPublisher inventoryEvents;

  /** Consumes product events and updates inventory denormalization. */
  @KafkaListener(
      topics = "product.events",
      groupId = "inventory-service-product-listener",
      containerFactory = "kafkaListenerContainerFactory")
  public void onProductEvent(ProductEvent event) {
    UUID productId = event.getProductId();

    try {
      switch (event.getEventType()) {
        case PRODUCT_CREATED:
          log.info(
              "Received PRODUCT_CREATED event productId={} initialQuantity={}",
              productId,
              event.getInitialQuantity());
          provisionOrCompensate(event);
          break;

        case PRODUCT_UPDATED:
          log.info("Received PRODUCT_UPDATED event productId={}", productId);
          if (event.getPayload() != null) {
            inventory.denormalizeProductSnapshot(
                productId, event.getPayload().getName(), event.getPayload().getSku());
          }
          break;

        case PRODUCT_DELETED:
          log.info("Received PRODUCT_DELETED event productId={}", productId);
          inventory.removeIfPresent(productId);
          break;
      }

      log.info(
          "Successfully processed product event eventType={} productId={}",
          event.getEventType(),
          productId);
    } catch (ResourceNotFoundException e) {
      log.debug(
          "Product not found during event processing eventType={} productId={}: {}",
          event.getEventType(),
          productId,
          e.getMessage());
    } catch (Exception e) {
      log.error(
          "Error processing product event eventType={} productId={}: {}",
          event.getEventType(),
          productId,
          e.getMessage(),
          e);
    }
  }

  private void provisionOrCompensate(ProductEvent event) {
    UUID productId = event.getProductId();
    ProductSnapshot payload = event.getPayload();
    try {
      inventory.provisionWithQuantity(
          productId,
          event.getInitialQuantity(),
          payload != null ? payload.getName() : null,
          payload != null ? payload.getSku() : null);
    } catch (RuntimeException e) {
      log.error("Provisioning failed productId={}; publishing compensation", productId, e);
      inventoryEvents.publishNow(
          InventoryEventType.INVENTORY_PROVISIONING_FAILED,
          productId,
          event.getInitialQuantity(),
          e.getMessage());
    }
  }
}
