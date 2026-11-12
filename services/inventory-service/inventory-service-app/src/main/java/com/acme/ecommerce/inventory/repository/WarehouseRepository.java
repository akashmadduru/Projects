package com.acme.ecommerce.inventory.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Warehouses on plain SQL. */
@Repository
public class WarehouseRepository {

  public record Warehouse(
      UUID id, String code, String name, UUID sellerId, String pincode, String state, boolean active) {}

  private static final String COLS = "id, code, name, seller_id, pincode, state, active";

  private final JdbcClient jdbc;

  public WarehouseRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static Warehouse map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new Warehouse(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getObject("seller_id", UUID.class),
        rs.getString("pincode"),
        rs.getString("state"),
        rs.getBoolean("active"));
  }

  public List<Warehouse> list(UUID sellerId) {
    if (sellerId == null) {
      return jdbc.sql("SELECT " + COLS + " FROM inventory.warehouses ORDER BY code")
          .query(WarehouseRepository::map)
          .list();
    }
    return jdbc.sql("SELECT " + COLS + " FROM inventory.warehouses WHERE seller_id = :s ORDER BY code")
        .param("s", sellerId)
        .query(WarehouseRepository::map)
        .list();
  }

  public Optional<Warehouse> byCode(String code) {
    return jdbc.sql("SELECT " + COLS + " FROM inventory.warehouses WHERE code = :c")
        .param("c", code)
        .query(WarehouseRepository::map)
        .optional();
  }

  public Optional<Warehouse> byId(UUID id) {
    return jdbc.sql("SELECT " + COLS + " FROM inventory.warehouses WHERE id = :i")
        .param("i", id)
        .query(WarehouseRepository::map)
        .optional();
  }

  public List<Warehouse> byIds(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("SELECT " + COLS + " FROM inventory.warehouses WHERE id IN (:ids)")
        .param("ids", ids)
        .query(WarehouseRepository::map)
        .list();
  }

  public Warehouse insert(String code, String name, UUID sellerId, String pincode, String state) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO inventory.warehouses (id, code, name, seller_id, pincode, state)"
                + " VALUES (:id, :c, :n, :s, :p, :st)")
        .param("id", id)
        .param("c", code)
        .param("n", name)
        .param("s", sellerId)
        .param("p", pincode)
        .param("st", state)
        .update();
    return byId(id).orElseThrow();
  }

  public void update(UUID id, String name, String pincode, String state, boolean active) {
    jdbc.sql(
            "UPDATE inventory.warehouses SET name = :n, pincode = :p, state = :st, active = :a"
                + " WHERE id = :id")
        .param("n", name)
        .param("p", pincode)
        .param("st", state)
        .param("a", active)
        .param("id", id)
        .update();
  }
}
