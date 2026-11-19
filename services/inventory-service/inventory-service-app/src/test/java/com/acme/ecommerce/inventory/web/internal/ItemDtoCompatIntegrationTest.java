package com.acme.ecommerce.inventory.web.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.inventory.support.AbstractInventoryIntegrationTest;
import com.acme.ecommerce.inventory.web.internal.InternalReservationController.ItemDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Rolling-deploy compatibility of the reserve body, against the application's own mapper. */
class ItemDtoCompatIntegrationTest extends AbstractInventoryIntegrationTest {

  /** The ItemDto shape before sellerId existed (what the previous inventory build deserialised). */
  record OldItemDto(UUID productId, UUID variantId, UUID offerId, int quantity) {}

  @Autowired private ObjectMapper mapper;

  @Test
  @DisplayName("a body without sellerId (old order-service) deserialises with a null sellerId")
  void bodyWithoutSellerId() throws Exception {
    UUID p = UUID.randomUUID();
    ItemDto dto = mapper.readValue("{\"productId\":\"" + p + "\",\"quantity\":2}", ItemDto.class);

    assertThat(dto.productId()).isEqualTo(p);
    assertThat(dto.sellerId()).isNull();
  }

  @Test
  @DisplayName(
      "the pre-change shape tolerates the extra sellerId (new order-service, old inventory)")
  void oldShapeToleratesSellerId() throws Exception {
    UUID p = UUID.randomUUID();
    OldItemDto dto =
        mapper.readValue(
            "{\"productId\":\""
                + p
                + "\",\"quantity\":2,\"sellerId\":\""
                + UUID.randomUUID()
                + "\"}",
            OldItemDto.class);

    assertThat(dto.productId()).isEqualTo(p);
    assertThat(dto.quantity()).isEqualTo(2);
  }
}
