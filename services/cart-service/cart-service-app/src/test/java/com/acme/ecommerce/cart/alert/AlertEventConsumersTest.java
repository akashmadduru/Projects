package com.acme.ecommerce.cart.alert;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.acme.ecommerce.cart.alert.AlertEventConsumers.InventoryEvt;
import com.acme.ecommerce.common.web.kafka.KafkaJson;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Seller-scoped stock events reach the back-in-stock check (TD-P9-05, AC-4). */
class AlertEventConsumersTest {

  @Test
  @DisplayName(
      "a quantity event with extra sellerId/offerId fields still triggers the restock check")
  void sellerScopedEventTriggersRestock() {
    UUID product = UUID.randomUUID();
    UUID variant = UUID.randomUUID();
    String json =
        "{\"eventId\":\""
            + UUID.randomUUID()
            + "\",\"eventType\":\"INVENTORY_QUANTITY_CHANGED\","
            + "\"productId\":\""
            + product
            + "\",\"variantId\":\""
            + variant
            + "\","
            + "\"quantity\":6,\"sellerId\":\""
            + UUID.randomUUID()
            + "\","
            + "\"offerId\":\""
            + UUID.randomUUID()
            + "\"}";
    InventoryEvt event =
        KafkaJson.eventDeserializer(InventoryEvt.class, "*")
            .deserialize("inventory.events", json.getBytes(StandardCharsets.UTF_8));
    AlertService alerts = mock(AlertService.class);

    new AlertEventConsumers.Listeners(alerts).onInventory(event);

    verify(alerts).onRestock(product, variant, 6);
  }
}
