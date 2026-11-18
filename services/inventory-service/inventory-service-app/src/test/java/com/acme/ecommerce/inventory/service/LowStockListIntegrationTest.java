package com.acme.ecommerce.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.inventory.api.dto.InventoryResponse;
import com.acme.ecommerce.inventory.support.AbstractInventoryIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/** TD-ADM-04: the low-stock list is filtered by each row's own threshold, server side. */
class LowStockListIntegrationTest extends AbstractInventoryIntegrationTest {

  @Autowired private InventoryService inventory;

  @Test
  @DisplayName("lists rows at or below their threshold and counts them in totalElements")
  void filtersByOwnThreshold() {
    UUID low = UUID.randomUUID();
    UUID plenty = UUID.randomUUID();
    inventory.provisionWithQuantity(low, 3, "Low", "L-1");
    inventory.provisionWithQuantity(plenty, 500, "Plenty", "P-1");

    var page = inventory.listLowStock(PageRequest.of(0, 200));
    List<UUID> ids = page.getContent().stream().map(InventoryResponse::getProductId).toList();

    assertThat(ids).contains(low).doesNotContain(plenty);
    assertThat(page.getTotalElements()).isEqualTo(page.getContent().size());
  }
}
