package com.acme.ecommerce.inventory.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Per-variant stock on plain SQL. A variant with no row is treated as unlimited by callers. */
@Repository
public class VariantStockRepository {

  public record VariantQty(UUID variantId, int quantity) {}

  private final JdbcClient jdbc;

  public VariantStockRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Locks the rows (ordered, to avoid deadlocks) and returns their quantities. */
  public List<VariantQty> lockAll(Collection<UUID> variantIds) {
    return jdbc.sql(
            "SELECT variant_id, quantity FROM inventory.variant_stock WHERE variant_id IN (:ids)"
                + " ORDER BY variant_id FOR UPDATE")
        .param("ids", variantIds)
        .query((rs, n) -> new VariantQty(rs.getObject("variant_id", UUID.class), rs.getInt("quantity")))
        .list();
  }

  public Optional<Integer> quantity(UUID variantId) {
    return jdbc.sql("SELECT quantity FROM inventory.variant_stock WHERE variant_id = :v")
        .param("v", variantId)
        .query(Integer.class)
        .optional();
  }

  public void adjust(UUID variantId, UUID productId, int delta) {
    jdbc.sql(
            "UPDATE inventory.variant_stock SET quantity = GREATEST(0, quantity + :d),"
                + " product_id = COALESCE(product_id, :p), updated_at = now() WHERE variant_id = :v")
        .param("d", delta)
        .param("p", productId)
        .param("v", variantId)
        .update();
  }

  public void upsert(UUID variantId, UUID productId, int quantity) {
    jdbc.sql(
            "INSERT INTO inventory.variant_stock (variant_id, product_id, quantity) VALUES (:v, :p, :q)"
                + " ON CONFLICT (variant_id) DO UPDATE SET quantity = EXCLUDED.quantity,"
                + " product_id = COALESCE(EXCLUDED.product_id, inventory.variant_stock.product_id),"
                + " updated_at = now()")
        .param("v", variantId)
        .param("p", productId)
        .param("q", quantity)
        .update();
  }

  public List<VariantQty> byProduct(UUID productId, Collection<UUID> knownIds) {
    return jdbc.sql(
            "SELECT variant_id, quantity FROM inventory.variant_stock WHERE product_id = :p"
                + " ORDER BY variant_id")
        .param("p", productId)
        .query((rs, n) -> new VariantQty(rs.getObject("variant_id", UUID.class), rs.getInt("quantity")))
        .list();
  }
}
