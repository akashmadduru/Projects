package com.acme.ecommerce.product.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ecommerce.product.client.InventoryDto;
import com.acme.ecommerce.product.client.InventoryServiceClient;
import com.acme.ecommerce.product.support.AbstractProductIntegrationTest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** TD-P6-04 / TD-P7-07: every active product and every variant is sent to inventory. */
@Transactional
class CatalogStockSyncIntegrationTest extends AbstractProductIntegrationTest {

  @MockitoBean private InventoryServiceClient inventory;
  @Autowired private CatalogStockSync sync;
  @Autowired private JdbcTemplate jdbc;

  @Test
  @DisplayName("sends every active product and every variant; inactive products are skipped")
  void sendsActiveProductsAndVariants() {
    List<UUID> active =
        jdbc.queryForList(
            "select id from product.products where active order by id limit 2", UUID.class);
    assertThat(active).hasSize(2);
    UUID withVariant = active.get(0);
    UUID retired = active.get(1);
    UUID variant = UUID.randomUUID();
    jdbc.update(
        "insert into product.product_variants (id, product_id, sku) values (?, ?, ?)",
        variant,
        withVariant,
        "SYNC-TEST-" + variant);
    jdbc.update("update product.products set active = false where id = ?", retired);
    when(inventory.ensureStock(any(), anyString())).thenReturn(new InventoryDto.EnsureResult(1));

    sync.sync();

    ArgumentCaptor<InventoryDto.EnsureRequest> sent =
        ArgumentCaptor.forClass(InventoryDto.EnsureRequest.class);
    verify(inventory, atLeastOnce()).ensureStock(sent.capture(), anyString());
    Set<InventoryDto.EnsureItem> items = new HashSet<>();
    sent.getAllValues().forEach(r -> items.addAll(r.items()));

    Set<InventoryDto.EnsureItem> expected = new HashSet<>();
    jdbc.queryForList("select id from product.products where active", UUID.class)
        .forEach(id -> expected.add(new InventoryDto.EnsureItem(id, null)));
    jdbc.query(
        "select v.product_id, v.id from product.product_variants v"
            + " join product.products p on p.id = v.product_id where p.active",
        rs -> {
          expected.add(
              new InventoryDto.EnsureItem(
                  rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)));
        });
    assertThat(items).isEqualTo(expected);
    assertThat(items).contains(new InventoryDto.EnsureItem(withVariant, variant));
    assertThat(items).doesNotContain(new InventoryDto.EnsureItem(retired, null));
    sent.getAllValues().forEach(r -> assertThat(r.items()).hasSizeLessThanOrEqualTo(500));
  }
}
