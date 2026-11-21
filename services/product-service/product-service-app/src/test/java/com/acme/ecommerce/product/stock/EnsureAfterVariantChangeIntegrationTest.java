package com.acme.ecommerce.product.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.product.client.InventoryDto;
import com.acme.ecommerce.product.client.InventoryServiceClient;
import com.acme.ecommerce.product.support.AbstractProductIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** TD-P7-22: a product's stock rows are ensured right away, not only by the 15-minute pass. */
class EnsureAfterVariantChangeIntegrationTest extends AbstractProductIntegrationTest {

  @MockitoBean private InventoryServiceClient inventory;
  @Autowired private CatalogStockSync sync;
  @Autowired private JdbcTemplate jdbc;

  @Test
  @DisplayName("sends the product and each of its variants to inventory")
  void ensuresOneProduct() {
    UUID product =
        jdbc.queryForObject(
            "select id from product.products where active order by id limit 1", UUID.class);
    UUID variant = UUID.randomUUID();
    jdbc.update(
        "insert into product.product_variants (id, product_id, sku) values (?, ?, ?)",
        variant,
        product,
        "ENSURE-TEST-" + variant);
    when(inventory.ensureStock(any(), anyString())).thenReturn(new InventoryDto.EnsureResult(2));
    try {
      sync.ensureProductAfterCommit(product);

      ArgumentCaptor<InventoryDto.EnsureRequest> sent =
          ArgumentCaptor.forClass(InventoryDto.EnsureRequest.class);
      verify(inventory, timeout(5000)).ensureStock(sent.capture(), anyString());
      List<InventoryDto.EnsureItem> items = sent.getValue().items();
      assertThat(items)
          .contains(
              new InventoryDto.EnsureItem(product, null),
              new InventoryDto.EnsureItem(product, variant))
          .allMatch(i -> i.productId().equals(product));
    } finally {
      jdbc.update("delete from product.product_variants where id = ?", variant);
    }
  }
}
