package com.acme.ecommerce.inventory.repository;

import com.acme.ecommerce.inventory.domain.Platform;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stock per (product, variant, seller, warehouse) on plain SQL. */
@Repository
public class WarehouseStockRepository {

  public record Row(
      UUID id,
      UUID productId,
      UUID variantId,
      UUID sellerId,
      UUID offerId,
      UUID warehouseId,
      String warehouseCode,
      String pincode,
      int quantity) {}

  private static final String SELECT =
      "SELECT ws.id, ws.product_id, ws.variant_id, ws.seller_id, ws.offer_id, ws.warehouse_id,"
          + " w.code, w.pincode, ws.quantity FROM inventory.warehouse_stock ws"
          + " JOIN inventory.warehouses w ON w.id = ws.warehouse_id";

  private static final String ZERO = "'00000000-0000-0000-0000-000000000000'::uuid";

  private final JdbcClient jdbc;

  public WarehouseStockRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static Row map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getObject("product_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("seller_id", UUID.class),
        rs.getObject("offer_id", UUID.class),
        rs.getObject("warehouse_id", UUID.class),
        rs.getString("code"),
        rs.getString("pincode"),
        rs.getInt("quantity"));
  }

  /** Locks the candidate rows of a line (active warehouses only). offerId null = platform stock. */
  public List<Row> lockForLine(UUID productId, UUID variantId, UUID offerId) {
    String where =
        offerId != null
            ? " WHERE ws.offer_id = :o AND ws.product_id = :p AND ws.variant_key = COALESCE(:v, " + ZERO + ")"
            : " WHERE ws.seller_id = :s AND ws.product_id = :p AND ws.variant_key = COALESCE(:v, " + ZERO + ")";
    JdbcClient.StatementSpec spec =
        jdbc.sql(SELECT + where + " AND w.active ORDER BY ws.id FOR UPDATE OF ws")
            .param("p", productId)
            .param("v", variantId, java.sql.Types.OTHER);
    spec = offerId != null ? spec.param("o", offerId) : spec.param("s", Platform.SELLER_ID);
    return spec.query(WarehouseStockRepository::map).list();
  }

  public List<Row> byProduct(UUID productId) {
    return jdbc.sql(SELECT + " WHERE ws.product_id = :p ORDER BY w.code")
        .param("p", productId)
        .query(WarehouseStockRepository::map)
        .list();
  }

  public List<Row> bySeller(UUID sellerId) {
    return jdbc.sql(SELECT + " WHERE ws.seller_id = :s ORDER BY ws.product_id, w.code")
        .param("s", sellerId)
        .query(WarehouseStockRepository::map)
        .list();
  }

  public Optional<Row> find(UUID productId, UUID variantId, UUID sellerId, UUID warehouseId) {
    return jdbc.sql(
            SELECT
                + " WHERE ws.product_id = :p AND ws.variant_key = COALESCE(:v, "
                + ZERO
                + ") AND ws.seller_id = :s AND ws.warehouse_id = :w")
        .param("p", productId)
        .param("v", variantId, java.sql.Types.OTHER)
        .param("s", sellerId)
        .param("w", warehouseId)
        .query(WarehouseStockRepository::map)
        .optional();
  }

  public void add(UUID rowId, int delta) {
    jdbc.sql(
            "UPDATE inventory.warehouse_stock SET quantity = GREATEST(0, quantity + :d),"
                + " updated_at = now() WHERE id = :id")
        .param("d", delta)
        .param("id", rowId)
        .update();
  }

  /** Sets the absolute quantity, creating the row if needed. Returns the previous quantity. */
  public int upsert(
      UUID productId, UUID variantId, UUID sellerId, UUID offerId, UUID warehouseId, int quantity) {
    int previous = find(productId, variantId, sellerId, warehouseId).map(Row::quantity).orElse(0);
    jdbc.sql(
            "INSERT INTO inventory.warehouse_stock"
                + " (product_id, variant_id, seller_id, offer_id, warehouse_id, quantity)"
                + " VALUES (:p, :v, :s, :o, :w, :q)"
                + " ON CONFLICT (product_id, variant_key, seller_id, warehouse_id)"
                + " DO UPDATE SET quantity = EXCLUDED.quantity,"
                + " offer_id = COALESCE(EXCLUDED.offer_id, inventory.warehouse_stock.offer_id),"
                + " updated_at = now()")
        .param("p", productId)
        .param("v", variantId, java.sql.Types.OTHER)
        .param("s", sellerId)
        .param("o", offerId, java.sql.Types.OTHER)
        .param("w", warehouseId)
        .param("q", quantity)
        .update();
    return previous;
  }

  /** Adds a delta to the platform's row in the given warehouse (creating it when absent). */
  public void shift(UUID productId, UUID variantId, UUID warehouseId, int delta) {
    Optional<Row> row = find(productId, variantId, Platform.SELLER_ID, warehouseId);
    if (row.isPresent()) {
      add(row.get().id(), delta);
    } else if (delta > 0) {
      upsert(productId, variantId, Platform.SELLER_ID, null, warehouseId, delta);
    }
  }

  public void deleteByProduct(UUID productId) {
    jdbc.sql("DELETE FROM inventory.warehouse_stock WHERE product_id = :p")
        .param("p", productId)
        .update();
  }

  public List<Row> platformByProducts(Collection<UUID> productIds) {
    if (productIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(SELECT + " WHERE ws.product_id IN (:ids) AND ws.seller_id = :s")
        .param("ids", productIds)
        .param("s", Platform.SELLER_ID)
        .query(WarehouseStockRepository::map)
        .list();
  }
}
