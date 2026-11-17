package com.acme.ecommerce.product.moderation;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.product.domain.Product;
import com.acme.ecommerce.product.repository.ProductRepository;
import com.acme.ecommerce.product.service.ProductCatalogService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Review queue for products listed by marketplace sellers (TD-P9-03).
 *
 * <p>A seller's new product is created inactive and PENDING_REVIEW. The queue shows each one with
 * automatic warnings (no image, no description, a banned term, a price far from its category's
 * average) so a reviewer can decide quickly; the warnings never decide on their own. Approving
 * activates the product; rejecting keeps it hidden and records a note the seller sees. A seller
 * editing a rejected product puts it back in the queue.
 */
@Service
public class ProductModerationService {

  public static final Set<String> STATUSES = Set.of("PENDING_REVIEW", "APPROVED", "REJECTED");

  /** Price more than this many times the category average (or less than 1/x of it) is flagged. */
  static final BigDecimal OUTLIER_FACTOR = BigDecimal.valueOf(5);

  public record QueueItem(
      UUID id,
      String sku,
      String name,
      String sellerName,
      UUID sellerId,
      BigDecimal price,
      String currency,
      String thumbnailUrl,
      Instant createdAt,
      String status,
      String note,
      List<String> flags) {}

  public record PageMeta(int size, int number, long totalElements, int totalPages) {}

  public record QueuePage(List<QueueItem> content, PageMeta page) {}

  private final JdbcClient jdbc;
  private final ProductRepository products;
  private final ProductCatalogService catalog;
  private final TimeProvider time;
  private final List<String> bannedTerms;

  public ProductModerationService(
      JdbcClient jdbc,
      ProductRepository products,
      ProductCatalogService catalog,
      TimeProvider time,
      @Value("${product.moderation.banned-terms:replica,counterfeit,fake,knockoff,first copy}")
          String bannedTerms) {
    this.jdbc = jdbc;
    this.products = products;
    this.catalog = catalog;
    this.time = time;
    this.bannedTerms =
        Arrays.stream(bannedTerms.split(","))
            .map(t -> t.trim().toLowerCase(Locale.ROOT))
            .filter(t -> !t.isEmpty())
            .toList();
  }

  @Transactional(readOnly = true)
  public QueuePage queue(String status, int page, int size) {
    String s =
        status == null || status.isBlank() ? "PENDING_REVIEW" : status.toUpperCase(Locale.ROOT);
    if (!STATUSES.contains(s)) {
      throw new CodedException(ErrorCode.VALIDATION_FAILED, "Unknown moderation status: " + status);
    }
    int p = Math.max(page, 0);
    int sz = Math.min(Math.max(size, 1), 50);
    long total =
        jdbc.sql("SELECT count(*) FROM product.products WHERE moderation_status = :s")
            .param("s", s)
            .query(Long.class)
            .single();
    List<QueueItem> rows =
        jdbc.sql(
                "SELECT p.id, p.sku, p.name, p.seller_name, p.seller_id, p.price_amount,"
                    + " p.price_currency, p.thumbnail_url, p.created_at, p.moderation_status,"
                    + " p.moderation_note, p.description,"
                    + " (SELECT count(*) FROM product.product_images i WHERE i.product_id = p.id)"
                    + "   AS images,"
                    + " (SELECT avg(c.price_amount) FROM product.products c WHERE c.active"
                    + "   AND c.category_id = p.category_id AND c.id <> p.id) AS category_avg"
                    + " FROM product.products p WHERE p.moderation_status = :s"
                    + " ORDER BY p.created_at, p.id LIMIT :lim OFFSET :off")
            .param("s", s)
            .param("lim", sz)
            .param("off", (long) p * sz)
            .query(
                (rs, n) -> {
                  BigDecimal price = rs.getBigDecimal("price_amount");
                  String name = rs.getString("name");
                  String description = rs.getString("description");
                  String thumbnail = rs.getString("thumbnail_url");
                  return new QueueItem(
                      rs.getObject("id", UUID.class),
                      rs.getString("sku"),
                      name,
                      rs.getString("seller_name"),
                      rs.getObject("seller_id", UUID.class),
                      price,
                      rs.getString("price_currency").trim(),
                      thumbnail,
                      rs.getTimestamp("created_at").toInstant(),
                      rs.getString("moderation_status"),
                      rs.getString("moderation_note"),
                      flags(
                          name,
                          description,
                          thumbnail != null || rs.getLong("images") > 0,
                          price,
                          rs.getBigDecimal("category_avg")));
                })
            .list();
    return new QueuePage(rows, new PageMeta(sz, p, total, (int) ((total + sz - 1) / sz)));
  }

  /** Automatic warnings for a reviewer. Never approve or reject on their own. */
  List<String> flags(
      String name, String description, boolean hasImage, BigDecimal price, BigDecimal categoryAvg) {
    List<String> out = new ArrayList<>();
    if (!hasImage) {
      out.add("NO_IMAGE");
    }
    if (description == null || description.isBlank()) {
      out.add("NO_DESCRIPTION");
    }
    String text =
        ((name == null ? "" : name) + " " + (description == null ? "" : description))
            .toLowerCase(Locale.ROOT);
    if (bannedTerms.stream().anyMatch(text::contains)) {
      out.add("BANNED_TERM");
    }
    if (price != null && categoryAvg != null && categoryAvg.signum() > 0) {
      if (price.compareTo(categoryAvg.multiply(OUTLIER_FACTOR)) > 0
          || price.multiply(OUTLIER_FACTOR).compareTo(categoryAvg) < 0) {
        out.add("PRICE_OUTLIER");
      }
    }
    return out;
  }

  /** APPROVE activates the product; REJECT needs a note and keeps it hidden. */
  @Transactional
  public QueueItem decide(UUID productId, String decision, String note, UUID reviewer) {
    Product p =
        products
            .findById(productId)
            .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
    String d = decision == null ? "" : decision.toUpperCase(Locale.ROOT);
    Instant now = time.now();
    switch (d) {
      case "APPROVE" -> {
        p.setActive(true);
        p.setModerationStatus("APPROVED");
        p.setModerationNote(note == null || note.isBlank() ? null : note.trim());
      }
      case "REJECT" -> {
        if (note == null || note.isBlank()) {
          throw new CodedException(
              ErrorCode.VALIDATION_FAILED, "A rejection needs a note for the seller");
        }
        p.setActive(false);
        p.setModerationStatus("REJECTED");
        p.setModerationNote(note.trim());
      }
      default ->
          throw new CodedException(
              ErrorCode.VALIDATION_FAILED, "decision must be APPROVE or REJECT");
    }
    p.setReviewedBy(reviewer);
    p.setReviewedAt(now);
    p.setUpdatedAt(now);
    products.saveAndFlush(p);
    catalog.publishUpdated(productId);
    return new QueueItem(
        p.getId(),
        p.getSku(),
        p.getName(),
        p.getSellerName(),
        p.getSellerId(),
        p.getPriceAmount(),
        p.getPriceCurrency(),
        p.getThumbnailUrl(),
        p.getCreatedAt(),
        p.getModerationStatus(),
        p.getModerationNote(),
        List.of());
  }
}
