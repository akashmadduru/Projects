package com.acme.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.acme.ecommerce.order.client.InventoryFeignClient;
import com.acme.ecommerce.order.domain.Order;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The reserve request carries each line's owner so inventory can fail closed for seller offers. */
class OrderRemoteReserveMappingTest {

  @Test
  @DisplayName("platform line sends PLATFORM and seller line sends that seller on the reserve item")
  void sendsSellerIdPerLine() {
    InventoryFeignClient inventory = mock(InventoryFeignClient.class);
    OrderRemote remote = new OrderRemote(inventory, null, null, null, null, null, null);
    UUID seller = UUID.randomUUID();
    UUID offer = UUID.randomUUID();
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();

    remote.reserveStock(
        UUID.randomUUID(),
        List.of(
            new OrderRemote.StockLine(p1, null, null, 1, Order.PLATFORM_SELLER),
            new OrderRemote.StockLine(p2, null, offer, 2, seller)),
        "560001");

    ArgumentCaptor<InventoryFeignClient.ReserveRequest> req =
        ArgumentCaptor.forClass(InventoryFeignClient.ReserveRequest.class);
    verify(inventory).reserve(req.capture());
    assertThat(req.getValue().items())
        .extracting(InventoryFeignClient.Item::productId, InventoryFeignClient.Item::sellerId)
        .containsExactly(tuple(p1, Order.PLATFORM_SELLER), tuple(p2, seller));
    assertThat(req.getValue().items().get(1).offerId()).isEqualTo(offer);
  }
}
