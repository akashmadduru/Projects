package com.acme.ecommerce.cart.repository;

import com.acme.ecommerce.cart.api.dto.CartItemRequest;
import com.acme.ecommerce.cart.api.dto.CartItemResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Cart persistence on plain SQL: every write is an atomic upsert ({@code INSERT ... ON CONFLICT}),
 * so concurrent requests for the same user and product cannot create duplicates or lose updates.
 */
@Repository
public class CartRepository {

  public static final int MAX_QUANTITY = 99;

  private final JdbcClient jdbc;

  public CartRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Returns the user cart id, creating the cart if needed. The conflict branch touches the row,
   * which takes its row lock - so concurrent cart mutations of one user serialise per transaction.
   */
  public UUID lockOrCreateCart(UUID userId) {
    return jdbc.sql(
            "INSERT INTO cart.carts (user_id) VALUES (:userId) "
                + "ON CONFLICT (user_id) DO UPDATE SET updated_at = now() RETURNING id")
        .param("userId", userId)
        .query(UUID.class)
        .single();
  }

  public List<CartItemResponse> findItems(UUID userId) {
    return jdbc.sql(
            "SELECT i.product_id, i.quantity, i.name, i.unit_price, i.currency, i.image_url, "
                + "i.added_at FROM cart.cart_items i JOIN cart.carts c ON c.id = i.cart_id "
                + "WHERE c.user_id = :userId ORDER BY i.added_at, i.id")
        .param("userId", userId)
        .query(
            (rs, n) -> {
              BigDecimal price = rs.getBigDecimal("unit_price");
              int qty = rs.getInt("quantity");
              return new CartItemResponse(
                  rs.getObject("product_id", UUID.class),
                  qty,
                  rs.getString("name"),
                  price,
                  rs.getString("currency"),
                  rs.getString("image_url"),
                  price.multiply(BigDecimal.valueOf(qty)),
                  rs.getObject("added_at", OffsetDateTime.class).toInstant());
            })
        .list();
  }

  /** Adds the requested quantity to the line (capped at 99), refreshing the display snapshot. */
  public void addOrIncrement(UUID cartId, CartItemRequest item) {
    jdbc.sql(
            "INSERT INTO cart.cart_items (cart_id, product_id, quantity, name, unit_price, "
                + "currency, image_url) VALUES (:cartId, :productId, :qty, :name, :price, "
                + ":currency, :imageUrl) "
                + "ON CONFLICT (cart_id, product_id) DO UPDATE SET "
                + "quantity = LEAST("
                + MAX_QUANTITY
                + ", cart.cart_items.quantity + EXCLUDED.quantity), "
                + "name = EXCLUDED.name, unit_price = EXCLUDED.unit_price, "
                + "currency = EXCLUDED.currency, image_url = EXCLUDED.image_url")
        .param("cartId", cartId)
        .param("productId", item.productId())
        .param("qty", item.quantity())
        .param("name", item.name().trim())
        .param("price", item.unitPrice())
        .param("currency", item.currency().toUpperCase(Locale.ROOT))
        .param("imageUrl", item.imageUrl())
        .update();
  }

  /** Returns rows changed (0 when the product is not in the cart). */
  public int setQuantity(UUID cartId, UUID productId, int quantity) {
    return jdbc.sql(
            "UPDATE cart.cart_items SET quantity = :qty WHERE cart_id = :cartId "
                + "AND product_id = :productId")
        .param("qty", quantity)
        .param("cartId", cartId)
        .param("productId", productId)
        .update();
  }

  public void deleteItem(UUID cartId, UUID productId) {
    jdbc.sql("DELETE FROM cart.cart_items WHERE cart_id = :cartId AND product_id = :productId")
        .param("cartId", cartId)
        .param("productId", productId)
        .update();
  }

  public void clear(UUID cartId) {
    jdbc.sql("DELETE FROM cart.cart_items WHERE cart_id = :cartId")
        .param("cartId", cartId)
        .update();
  }

  /** Distinct currencies of ALL current cart lines (including the product being re-added). */
  public List<String> currencies(UUID cartId) {
    return jdbc.sql("SELECT DISTINCT currency FROM cart.cart_items WHERE cart_id = :cartId")
        .param("cartId", cartId)
        .query(String.class)
        .list();
  }

  public int countLines(UUID cartId) {
    return jdbc.sql("SELECT count(*) FROM cart.cart_items WHERE cart_id = :cartId")
        .param("cartId", cartId)
        .query(Integer.class)
        .single();
  }

  public boolean hasLine(UUID cartId, UUID productId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM cart.cart_items WHERE cart_id = :cartId "
                + "AND product_id = :productId)")
        .param("cartId", cartId)
        .param("productId", productId)
        .query(Boolean.class)
        .single();
  }
}
