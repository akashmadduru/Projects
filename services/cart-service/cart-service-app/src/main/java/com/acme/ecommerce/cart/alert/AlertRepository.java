package com.acme.ecommerce.cart.alert;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Price-drop / back-in-stock alerts on plain SQL. */
@Repository
public class AlertRepository {

  private static final UUID NIL = new UUID(0L, 0L);

  public record Alert(
      UUID id,
      UUID userId,
      String email,
      UUID productId,
      UUID variantId,
      String productName,
      String type,
      BigDecimal targetPrice,
      BigDecimal lastPrice,
      String currency,
      boolean active,
      OffsetDateTime triggeredAt,
      OffsetDateTime createdAt) {}

  private final JdbcClient jdbc;

  public AlertRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static Alert map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new Alert(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("email"),
        rs.getObject("product_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("product_name"),
        rs.getString("type"),
        rs.getBigDecimal("target_price"),
        rs.getBigDecimal("last_price"),
        rs.getString("currency"),
        rs.getBoolean("active"),
        rs.getObject("triggered_at", OffsetDateTime.class),
        rs.getObject("created_at", OffsetDateTime.class));
  }

  public List<Alert> findByUser(UUID userId) {
    return jdbc.sql("SELECT * FROM cart.alerts WHERE user_id = :u ORDER BY created_at DESC")
        .param("u", userId)
        .query(AlertRepository::map)
        .list();
  }

  public int countByUser(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM cart.alerts WHERE user_id = :u")
        .param("u", userId)
        .query(Integer.class)
        .single();
  }

  /** Creates or re-arms (active = true, triggered_at kept for the 24 h email rate limit). */
  public UUID upsert(
      UUID userId,
      String email,
      UUID productId,
      UUID variantId,
      String productName,
      String type,
      BigDecimal targetPrice,
      BigDecimal lastPrice,
      String currency) {
    return jdbc.sql(
            "INSERT INTO cart.alerts (user_id, email, product_id, variant_id, product_name, type,"
                + " target_price, last_price, currency) VALUES (:u, :e, :p, :v, :n, :t, :tp, :lp, :c)"
                + " ON CONFLICT (user_id, product_id, variant_key, type) DO UPDATE SET active = true,"
                + " email = EXCLUDED.email, target_price = EXCLUDED.target_price,"
                + " last_price = EXCLUDED.last_price, currency = EXCLUDED.currency,"
                + " product_name = COALESCE(EXCLUDED.product_name, cart.alerts.product_name)"
                + " RETURNING id")
        .param("u", userId)
        .param("e", email)
        .param("p", productId)
        .param("v", variantId)
        .param("n", productName)
        .param("t", type)
        .param("tp", targetPrice)
        .param("lp", lastPrice)
        .param("c", currency)
        .query(UUID.class)
        .single();
  }

  public Alert findById(UUID id) {
    return jdbc.sql("SELECT * FROM cart.alerts WHERE id = :id")
        .param("id", id)
        .query(AlertRepository::map)
        .single();
  }

  public int delete(UUID id, UUID userId) {
    return jdbc.sql("DELETE FROM cart.alerts WHERE id = :id AND user_id = :u")
        .param("id", id)
        .param("u", userId)
        .update();
  }

  public List<Alert> activeFor(UUID productId, String type) {
    return jdbc.sql(
            "SELECT * FROM cart.alerts WHERE product_id = :p AND type = :t AND active")
        .param("p", productId)
        .param("t", type)
        .query(AlertRepository::map)
        .list();
  }

  public List<Alert> activeFor(UUID productId, UUID variantId, String type) {
    return jdbc.sql(
            "SELECT * FROM cart.alerts WHERE product_id = :p AND type = :t AND active"
                + " AND variant_key = :vk")
        .param("p", productId)
        .param("t", type)
        .param("vk", variantId == null ? NIL : variantId)
        .query(AlertRepository::map)
        .list();
  }

  public void updateLastPrice(UUID id, BigDecimal price) {
    jdbc.sql("UPDATE cart.alerts SET last_price = :p WHERE id = :id")
        .param("p", price)
        .param("id", id)
        .update();
  }

  public void markTriggered(UUID id, BigDecimal price) {
    jdbc.sql(
            "UPDATE cart.alerts SET active = false, triggered_at = now(),"
                + " last_price = COALESCE(:p, last_price) WHERE id = :id")
        .param("p", price)
        .param("id", id)
        .update();
  }
}
