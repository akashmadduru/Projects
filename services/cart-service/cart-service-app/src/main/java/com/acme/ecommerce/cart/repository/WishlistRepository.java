package com.acme.ecommerce.cart.repository;

import com.acme.ecommerce.cart.api.dto.WishlistItemResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Wishlist persistence; all writes are idempotent upserts. */
@Repository
public class WishlistRepository {

  private static final String INSERT =
      "INSERT INTO cart.wishlist_items (user_id, product_id, name, price, currency, image_url) "
          + "VALUES (:userId, :productId, :name, :price, :currency, :imageUrl) "
          + "ON CONFLICT (user_id, product_id) DO ";

  private final JdbcClient jdbc;

  public WishlistRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public List<WishlistItemResponse> findAll(UUID userId) {
    return jdbc.sql(
            "SELECT product_id, name, price, currency, image_url, added_at "
                + "FROM cart.wishlist_items WHERE user_id = :userId ORDER BY added_at DESC, id")
        .param("userId", userId)
        .query(
            (rs, n) ->
                new WishlistItemResponse(
                    rs.getObject("product_id", UUID.class),
                    rs.getString("name"),
                    rs.getBigDecimal("price"),
                    rs.getString("currency"),
                    rs.getString("image_url"),
                    rs.getObject("added_at", OffsetDateTime.class).toInstant()))
        .list();
  }

  /** Inserts or refreshes the snapshot; added_at is kept from the first insert. */
  public void upsert(
      UUID userId,
      UUID productId,
      String name,
      BigDecimal price,
      String currency,
      String imageUrl) {
    write(
        INSERT
            + "UPDATE SET name = EXCLUDED.name, price = EXCLUDED.price, "
            + "currency = EXCLUDED.currency, image_url = EXCLUDED.image_url",
        userId,
        productId,
        name,
        price,
        currency,
        imageUrl);
  }

  /** Union-merge insert: an entry already on the server wishlist is left untouched. */
  public void insertIfAbsent(
      UUID userId,
      UUID productId,
      String name,
      BigDecimal price,
      String currency,
      String imageUrl) {
    write(INSERT + "NOTHING", userId, productId, name, price, currency, imageUrl);
  }

  /** Serialises this user's wishlist writes for the rest of the transaction (cap enforcement). */
  public void lockUser(UUID userId) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .param("key", "wishlist:" + userId)
        .query()
        .singleRow();
  }

  public int count(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM cart.wishlist_items WHERE user_id = :userId")
        .param("userId", userId)
        .query(Integer.class)
        .single();
  }

  public boolean exists(UUID userId, UUID productId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM cart.wishlist_items WHERE user_id = :userId "
                + "AND product_id = :productId)")
        .param("userId", userId)
        .param("productId", productId)
        .query(Boolean.class)
        .single();
  }

  public void delete(UUID userId, UUID productId) {
    jdbc.sql("DELETE FROM cart.wishlist_items WHERE user_id = :userId AND product_id = :productId")
        .param("userId", userId)
        .param("productId", productId)
        .update();
  }

  private void write(
      String sql,
      UUID userId,
      UUID productId,
      String name,
      BigDecimal price,
      String currency,
      String imageUrl) {
    jdbc.sql(sql)
        .param("userId", userId)
        .param("productId", productId)
        .param("name", name.trim())
        .param("price", price)
        .param("currency", currency.toUpperCase(Locale.ROOT))
        .param("imageUrl", imageUrl)
        .update();
  }
}
