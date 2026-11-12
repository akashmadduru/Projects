package com.acme.ecommerce.cart.lists;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multiple + shareable lists. The default list ("Wishlist") stores its items in
 * cart.wishlist_items (so /api/wishlist/** keeps working); other lists use cart.list_items.
 */
@Service
public class ListService {

  static final int MAX_LISTS = 20;
  static final int MAX_ITEMS = 200;
  private static final UUID NIL = new UUID(0L, 0L);
  private static final SecureRandom RANDOM = new SecureRandom();

  public record ListView(
      UUID id,
      String name,
      boolean isDefault,
      String visibility,
      String shareUrl,
      String shareToken,
      List<Item> items,
      int itemCount,
      OffsetDateTime createdAt) {}

  public record Item(
      UUID productId,
      UUID variantId,
      String variantLabel,
      String name,
      BigDecimal price,
      String currency,
      String imageUrl,
      String note,
      OffsetDateTime addedAt) {}

  public record ItemRequest(
      UUID productId,
      UUID variantId,
      String variantLabel,
      String note,
      String name,
      BigDecimal price,
      String currency,
      String imageUrl) {}

  public record SharedList(String name, String ownerFirstName, List<Item> items) {}

  private record Row(
      UUID id,
      UUID userId,
      String name,
      boolean isDefault,
      String visibility,
      String shareToken,
      OffsetDateTime createdAt) {}

  private final JdbcClient jdbc;
  private final String storefront;

  public ListService(
      JdbcClient jdbc, @Value("${cart.storefront-url:http://localhost:3000}") String storefront) {
    this.jdbc = jdbc;
    this.storefront = storefront;
  }

  @Transactional
  public List<ListView> list(UUID userId) {
    ensureDefault(userId);
    return jdbc.sql("SELECT * FROM cart.lists WHERE user_id = :u ORDER BY is_default DESC, created_at")
        .param("u", userId)
        .query(ListService::row)
        .list()
        .stream()
        .map(this::view)
        .toList();
  }

  @Transactional
  public ListView detail(UUID userId, UUID id) {
    Row r = owned(userId, id);
    return view(r);
  }

  @Transactional
  public ListView create(UUID userId, String name) {
    ensureDefault(userId);
    String n = cleanName(name);
    int count =
        jdbc.sql("SELECT count(*) FROM cart.lists WHERE user_id = :u")
            .param("u", userId)
            .query(Integer.class)
            .single();
    if (count >= MAX_LISTS) {
      throw new CodedException(ErrorCode.WISHLIST_LIMIT, "Lists are limited to " + MAX_LISTS);
    }
    UUID id =
        jdbc.sql("INSERT INTO cart.lists (user_id, name) VALUES (:u, :n) RETURNING id")
            .param("u", userId)
            .param("n", n)
            .query(UUID.class)
            .single();
    return view(owned(userId, id));
  }

  @Transactional
  public ListView rename(UUID userId, UUID id, String name) {
    Row r = owned(userId, id);
    if (r.isDefault()) {
      throw new CodedException(ErrorCode.DOMAIN_RULE_VIOLATION, "The default list cannot be renamed");
    }
    jdbc.sql("UPDATE cart.lists SET name = :n WHERE id = :id")
        .param("n", cleanName(name))
        .param("id", id)
        .update();
    return view(owned(userId, id));
  }

  @Transactional
  public void delete(UUID userId, UUID id) {
    Row r = owned(userId, id);
    if (r.isDefault()) {
      throw new CodedException(ErrorCode.DOMAIN_RULE_VIOLATION, "The default list cannot be deleted");
    }
    jdbc.sql("DELETE FROM cart.lists WHERE id = :id").param("id", id).update();
  }

  @Transactional
  public ListView addItem(UUID userId, UUID id, ItemRequest r) {
    Row list = owned(userId, id);
    if (r.productId() == null
        || r.name() == null
        || r.name().isBlank()
        || r.price() == null
        || r.currency() == null
        || r.currency().length() != 3) {
      throw new CodedException(
          ErrorCode.VALIDATION_FAILED, "productId, name, price and currency are required");
    }
    boolean exists = hasItem(list, r.productId(), r.variantId());
    if (!exists && itemCount(list) >= (list.isDefault() ? 500 : MAX_ITEMS)) {
      throw new CodedException(
          ErrorCode.WISHLIST_LIMIT, "Lists are limited to " + MAX_ITEMS + " items");
    }
    if (list.isDefault()) {
      jdbc.sql(
              "INSERT INTO cart.wishlist_items (user_id, product_id, variant_id, variant_label,"
                  + " name, price, currency, image_url, note) VALUES (:u, :p, :v, :vl, :n, :pr,"
                  + " :c, :img, :note) ON CONFLICT (user_id, product_id, variant_key) DO UPDATE SET"
                  + " name = EXCLUDED.name, price = EXCLUDED.price, currency = EXCLUDED.currency,"
                  + " image_url = EXCLUDED.image_url, variant_label = EXCLUDED.variant_label,"
                  + " note = EXCLUDED.note")
          .param("u", userId)
          .param("p", r.productId())
          .param("v", r.variantId())
          .param("vl", r.variantLabel())
          .param("n", r.name().trim())
          .param("pr", r.price())
          .param("c", r.currency().toUpperCase(Locale.ROOT))
          .param("img", r.imageUrl())
          .param("note", clip(r.note(), 300))
          .update();
    } else {
      jdbc.sql(
              "INSERT INTO cart.list_items (list_id, product_id, variant_id, variant_label, name,"
                  + " price, currency, image_url, note) VALUES (:l, :p, :v, :vl, :n, :pr, :c,"
                  + " :img, :note) ON CONFLICT (list_id, product_id, variant_key) DO UPDATE SET"
                  + " name = EXCLUDED.name, price = EXCLUDED.price, currency = EXCLUDED.currency,"
                  + " image_url = EXCLUDED.image_url, variant_label = EXCLUDED.variant_label,"
                  + " note = EXCLUDED.note")
          .param("l", id)
          .param("p", r.productId())
          .param("v", r.variantId())
          .param("vl", r.variantLabel())
          .param("n", r.name().trim())
          .param("pr", r.price())
          .param("c", r.currency().toUpperCase(Locale.ROOT))
          .param("img", r.imageUrl())
          .param("note", clip(r.note(), 300))
          .update();
    }
    return view(list);
  }

  @Transactional
  public void removeItem(UUID userId, UUID id, UUID productId, UUID variantId) {
    Row list = owned(userId, id);
    UUID vk = variantId == null ? NIL : variantId;
    if (list.isDefault()) {
      jdbc.sql(
              "DELETE FROM cart.wishlist_items WHERE user_id = :u AND product_id = :p AND"
                  + " variant_key = :vk")
          .param("u", userId)
          .param("p", productId)
          .param("vk", vk)
          .update();
    } else {
      jdbc.sql(
              "DELETE FROM cart.list_items WHERE list_id = :l AND product_id = :p AND variant_key"
                  + " = :vk")
          .param("l", id)
          .param("p", productId)
          .param("vk", vk)
          .update();
    }
  }

  @Transactional
  public ListView share(UUID userId, UUID id, String email) {
    Row r = owned(userId, id);
    String token = r.shareToken();
    if (token == null) {
      byte[] b = new byte[18];
      RANDOM.nextBytes(b);
      token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
    jdbc.sql(
            "UPDATE cart.lists SET visibility = 'SHARED', share_token = :t, owner_name ="
                + " COALESCE(:o, owner_name) WHERE id = :id")
        .param("t", token)
        .param("o", firstName(email))
        .param("id", id)
        .update();
    return view(owned(userId, id));
  }

  @Transactional
  public void unshare(UUID userId, UUID id) {
    owned(userId, id);
    jdbc.sql("UPDATE cart.lists SET visibility = 'PRIVATE', share_token = NULL WHERE id = :id")
        .param("id", id)
        .update();
  }

  @Transactional(readOnly = true)
  public SharedList shared(String token) {
    Row r =
        jdbc.sql("SELECT * FROM cart.lists WHERE share_token = :t AND visibility = 'SHARED'")
            .param("t", token)
            .query(ListService::row)
            .optional()
            .orElseThrow(
                () -> new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "Shared list not found"));
    String owner =
        jdbc.sql("SELECT owner_name FROM cart.lists WHERE id = :id")
            .param("id", r.id())
            .query(String.class)
            .optional()
            .orElse(null);
    return new SharedList(r.name(), owner == null ? "A friend" : owner, items(r));
  }

  // --- helpers --------------------------------------------------------------------------------

  private void ensureDefault(UUID userId) {
    jdbc.sql(
            "INSERT INTO cart.lists (user_id, name, is_default) VALUES (:u, 'Wishlist', true) ON"
                + " CONFLICT DO NOTHING")
        .param("u", userId)
        .update();
  }

  private Row owned(UUID userId, UUID id) {
    ensureDefault(userId);
    Optional<Row> r =
        jdbc.sql("SELECT * FROM cart.lists WHERE id = :id AND user_id = :u")
            .param("id", id)
            .param("u", userId)
            .query(ListService::row)
            .optional();
    return r.orElseThrow(() -> new CodedException(ErrorCode.RESOURCE_NOT_FOUND, "List not found"));
  }

  private boolean hasItem(Row list, UUID productId, UUID variantId) {
    UUID vk = variantId == null ? NIL : variantId;
    return list.isDefault()
        ? jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM cart.wishlist_items WHERE user_id = :u AND"
                    + " product_id = :p AND variant_key = :vk)")
            .param("u", list.userId())
            .param("p", productId)
            .param("vk", vk)
            .query(Boolean.class)
            .single()
        : jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM cart.list_items WHERE list_id = :l AND product_id ="
                    + " :p AND variant_key = :vk)")
            .param("l", list.id())
            .param("p", productId)
            .param("vk", vk)
            .query(Boolean.class)
            .single();
  }

  private int itemCount(Row list) {
    return list.isDefault()
        ? jdbc.sql("SELECT count(*) FROM cart.wishlist_items WHERE user_id = :u")
            .param("u", list.userId())
            .query(Integer.class)
            .single()
        : jdbc.sql("SELECT count(*) FROM cart.list_items WHERE list_id = :l")
            .param("l", list.id())
            .query(Integer.class)
            .single();
  }

  private List<Item> items(Row list) {
    String sql =
        list.isDefault()
            ? "SELECT product_id, variant_id, variant_label, name, price, currency, image_url,"
                + " note, added_at FROM cart.wishlist_items WHERE user_id = :k ORDER BY added_at"
                + " DESC, id"
            : "SELECT product_id, variant_id, variant_label, name, price, currency, image_url,"
                + " note, added_at FROM cart.list_items WHERE list_id = :k ORDER BY added_at DESC,"
                + " id";
    return jdbc.sql(sql)
        .param("k", list.isDefault() ? list.userId() : list.id())
        .query(
            (rs, n) ->
                new Item(
                    rs.getObject("product_id", UUID.class),
                    rs.getObject("variant_id", UUID.class),
                    rs.getString("variant_label"),
                    rs.getString("name"),
                    rs.getBigDecimal("price"),
                    rs.getString("currency").trim(),
                    rs.getString("image_url"),
                    rs.getString("note"),
                    rs.getObject("added_at", OffsetDateTime.class)))
        .list();
  }

  private ListView view(Row r) {
    return new ListView(
        r.id(),
        r.name(),
        r.isDefault(),
        r.visibility(),
        r.shareToken() == null ? null : storefront + "/lists/shared/" + r.shareToken(),
        r.shareToken(),
        items(r),
        itemCount(r),
        r.createdAt());
  }

  private static Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new Row(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("name"),
        rs.getBoolean("is_default"),
        rs.getString("visibility"),
        rs.getString("share_token"),
        rs.getObject("created_at", OffsetDateTime.class));
  }

  private static String cleanName(String name) {
    String n = name == null ? "" : name.trim();
    if (n.isEmpty() || n.length() > 60) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "Name must be 1-60 characters");
    }
    return n;
  }

  private static String clip(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }

  /** Best-effort first name from the email local part (no name claim in the JWT). */
  private static String firstName(String email) {
    if (email == null || email.isBlank()) {
      return null;
    }
    String local = email.split("@", 2)[0].split("[._+\\-0-9]", 2)[0];
    if (local.length() < 2) {
      return null;
    }
    return local.substring(0, 1).toUpperCase(Locale.ROOT) + local.substring(1).toLowerCase(Locale.ROOT);
  }
}
