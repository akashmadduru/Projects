package com.acme.ecommerce.cart.repository;

import com.acme.ecommerce.cart.api.dto.CartItemRequest;
import com.acme.ecommerce.cart.api.dto.CartItemResponse;
import com.acme.ecommerce.cart.api.dto.SavedItemResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Cart persistence on plain SQL: every write is an atomic upsert ({@code INSERT ... ON CONFLICT}),
 * so concurrent requests for the same user and product cannot create duplicates or lose updates.
 * A line is keyed by (product, variant); a null variant is stored as null and compared through the
 * generated {@code variant_key} column (nil uuid).
 */
@Repository
public class CartRepository {

  public static final int MAX_QUANTITY = 99;

  private static final UUID NIL = new UUID(0L, 0L);

  /** Offer predicate: an omitted offerId matches every offer of the (product, variant) line. */
  private static final String OFFER_ANY = " AND (CAST(:ok AS uuid) IS NULL OR offer_key = CAST(:ok AS uuid))";

  private final JdbcClient jdbc;

  public CartRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private static UUID key(UUID variantId) {
    return variantId == null ? NIL : variantId;
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
            "SELECT i.product_id, i.variant_id, i.variant_label, i.offer_id, i.seller_id, i.quantity, i.name, i.unit_price, "
                + "i.currency, i.image_url, i.added_at FROM cart.cart_items i JOIN cart.carts c "
                + "ON c.id = i.cart_id WHERE c.user_id = :userId ORDER BY i.added_at, i.id")
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
                  rs.getObject("added_at", OffsetDateTime.class).toInstant(),
                  rs.getObject("variant_id", UUID.class),
                  rs.getString("variant_label"),
                  rs.getObject("offer_id", UUID.class),
                  rs.getObject("seller_id", UUID.class));
            })
        .list();
  }

  /** Adds the requested quantity to the line (capped at 99), refreshing the display snapshot. */
  public void addOrIncrement(UUID cartId, CartItemRequest item) {
    jdbc.sql(
            "INSERT INTO cart.cart_items (cart_id, product_id, variant_id, variant_label, offer_id, seller_id, "
                + "quantity, name, unit_price, currency, image_url) VALUES (:cartId, :productId, :variantId, "
                + ":variantLabel, :offerId, :sellerId, :qty, :name, :price, :currency, :imageUrl) "
                + "ON CONFLICT (cart_id, product_id, variant_key, offer_key) DO UPDATE SET "
                + "quantity = LEAST("
                + MAX_QUANTITY
                + ", cart.cart_items.quantity + EXCLUDED.quantity), "
                + "name = EXCLUDED.name, unit_price = EXCLUDED.unit_price, "
                + "variant_label = EXCLUDED.variant_label, "
                + "currency = EXCLUDED.currency, image_url = EXCLUDED.image_url")
        .param("cartId", cartId)
        .param("productId", item.productId())
        .param("variantId", item.variantId())
        .param("variantLabel", item.variantLabel())
        .param("offerId", item.offerId())
        .param("sellerId", item.sellerId())
        .param("qty", item.quantity())
        .param("name", item.name().trim())
        .param("price", item.unitPrice())
        .param("currency", item.currency().toUpperCase(Locale.ROOT))
        .param("imageUrl", item.imageUrl())
        .update();
  }

  /** Returns rows changed (0 when the line is not in the cart). */
  public int setQuantity(UUID cartId, UUID productId, UUID variantId, UUID offerId, int quantity) {
    return jdbc.sql(
            "UPDATE cart.cart_items SET quantity = :qty WHERE cart_id = :cartId "
                + "AND product_id = :productId AND variant_key = :vk" + OFFER_ANY)
        .param("ok", offerId)
        .param("qty", quantity)
        .param("cartId", cartId)
        .param("productId", productId)
        .param("vk", key(variantId))
        .update();
  }

  public void deleteItem(UUID cartId, UUID productId, UUID variantId, UUID offerId) {
    jdbc.sql(
            "DELETE FROM cart.cart_items WHERE cart_id = :cartId AND product_id = :productId "
                + "AND variant_key = :vk" + OFFER_ANY)
        .param("ok", offerId)
        .param("cartId", cartId)
        .param("productId", productId)
        .param("vk", key(variantId))
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

  // --- save for later ---------------------------------------------------------------------

  public List<SavedItemResponse> findSaved(UUID userId) {
    return jdbc.sql(
            "SELECT product_id, variant_id, variant_label, offer_id, seller_id, name, unit_price, currency, image_url, "
                + "saved_at FROM cart.saved_items WHERE user_id = :userId "
                + "ORDER BY saved_at DESC, id")
        .param("userId", userId)
        .query(
            (rs, n) ->
                new SavedItemResponse(
                    rs.getObject("product_id", UUID.class),
                    rs.getString("name"),
                    rs.getBigDecimal("unit_price"),
                    rs.getString("currency"),
                    rs.getString("image_url"),
                    rs.getObject("saved_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("variant_id", UUID.class),
                    rs.getString("variant_label"),
                    rs.getObject("offer_id", UUID.class),
                    rs.getObject("seller_id", UUID.class)))
        .list();
  }

  public int countSaved(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM cart.saved_items WHERE user_id = :userId")
        .param("userId", userId)
        .query(Integer.class)
        .single();
  }

  public boolean hasSaved(UUID userId, UUID productId, UUID variantId, UUID offerId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM cart.saved_items WHERE user_id = :userId "
                + "AND product_id = :productId AND variant_key = :vk" + OFFER_ANY + ")")
        .param("ok", offerId)
        .param("userId", userId)
        .param("productId", productId)
        .param("vk", key(variantId))
        .query(Boolean.class)
        .single();
  }

  /** Copies the cart line into saved items (upsert). Returns rows written (0 = no such line). */
  public int saveLineForLater(
      UUID cartId, UUID userId, UUID productId, UUID variantId, UUID offerId) {
    return jdbc.sql(
            "INSERT INTO cart.saved_items (user_id, product_id, variant_id, variant_label, offer_id, seller_id, "
                + "name, unit_price, currency, image_url) SELECT :userId, product_id, variant_id, "
                + "variant_label, offer_id, seller_id, name, unit_price, currency, image_url "
                + "FROM cart.cart_items WHERE cart_id = :cartId AND product_id = :productId "
                + "AND variant_key = :vk" + OFFER_ANY + " "
                + "ON CONFLICT (user_id, product_id, variant_key, offer_key) DO UPDATE SET "
                + "name = EXCLUDED.name, "
                + "unit_price = EXCLUDED.unit_price, currency = EXCLUDED.currency, "
                + "image_url = EXCLUDED.image_url, saved_at = now()")
        .param("userId", userId)
        .param("cartId", cartId)
        .param("productId", productId)
        .param("vk", key(variantId))
        .param("ok", offerId)
        .update();
  }

  /** The saved row as a quantity-1 cart request, or empty. */
  public Optional<CartItemRequest> findSavedAsRequest(
      UUID userId, UUID productId, UUID variantId, UUID offerId) {
    return jdbc.sql(
            "SELECT product_id, variant_id, variant_label, offer_id, seller_id, name, unit_price, currency, image_url "
                + "FROM cart.saved_items WHERE user_id = :userId AND product_id = :productId "
                + "AND variant_key = :vk" + OFFER_ANY + " ORDER BY saved_at DESC LIMIT 1")
        .param("ok", offerId)
        .param("userId", userId)
        .param("productId", productId)
        .param("vk", key(variantId))
        .query(
            (rs, n) ->
                new CartItemRequest(
                    rs.getObject("product_id", UUID.class),
                    1,
                    rs.getString("name"),
                    rs.getBigDecimal("unit_price"),
                    rs.getString("currency"),
                    rs.getString("image_url"),
                    rs.getObject("variant_id", UUID.class),
                    rs.getString("variant_label"),
                    rs.getObject("offer_id", UUID.class),
                    rs.getObject("seller_id", UUID.class)))
        .optional();
  }

  public void deleteSaved(UUID userId, UUID productId, UUID variantId, UUID offerId) {
    jdbc.sql(
            "DELETE FROM cart.saved_items WHERE user_id = :userId AND product_id = :productId "
                + "AND variant_key = :vk" + OFFER_ANY)
        .param("ok", offerId)
        .param("userId", userId)
        .param("productId", productId)
        .param("vk", key(variantId))
        .update();
  }

  public boolean hasLine(UUID cartId, UUID productId, UUID variantId, UUID offerId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM cart.cart_items WHERE cart_id = :cartId "
                + "AND product_id = :productId AND variant_key = :vk AND offer_key = :okExact)")
        .param("okExact", key(offerId))
        .param("cartId", cartId)
        .param("productId", productId)
        .param("vk", key(variantId))
        .query(Boolean.class)
        .single();
  }
}
