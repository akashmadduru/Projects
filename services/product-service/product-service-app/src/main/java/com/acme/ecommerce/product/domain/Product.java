package com.acme.ecommerce.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A catalog product.
 *
 * <p>NO ASSOCIATIONS, therefore NO N+1 RISK. Both read endpoints read this table and nothing else,
 * so a page of 20 products is exactly one SELECT. The moment a {@code @ManyToOne category} lands
 * here, the paged endpoint needs an explicit fetch strategy or it becomes 21 queries.
 *
 * <p>PRICE IS {@code numeric}/{@link BigDecimal}, never {@code double}. Binary floating point
 * cannot represent 0.10 exactly; a money column that goes through a {@code double} accumulates
 * error that surfaces months later as a one-cent discrepancy nobody can reproduce.
 *
 * <p>{@code @Version} MIRRORS {@code auth.users.version} EXACTLY - optimistic concurrency via JPA's
 * own field, not a hand-rolled counter, and the same mechanism {@code Inventory}'s {@code version}
 * column uses.
 *
 * <p>NO {@code @PrePersist}/{@code @PreUpdate}, AND NO {@code Instant.now()} ANYWHERE IN THIS
 * CLASS. JPA lifecycle callbacks cannot receive constructor-injected dependencies, so the timestamp
 * assignment lives in the service layer, which already holds a {@code TimeProvider}: {@code now} is
 * an explicit parameter callers pass to {@link #builder()} and to {@link #update(String,
 * BigDecimal, String, boolean, UUID, String, String, Instant)} instead of an implicit callback side
 * effect that called {@code Instant.now()} directly - the fix mirrors {@code Inventory.java}, which
 * was built this way from day one so the anti-pattern was not propagated into new code.
 *
 * <p>{@code categoryId} IS A PLAIN {@code UUID} COLUMN, NOT A {@code @ManyToOne} ASSOCIATION - the
 * "NO ASSOCIATIONS" invariant above extends to the catalog-enrichment fields added in the Phase 3
 * slice: a page of 20 products is still exactly one {@code SELECT} against this table, never a join
 * against {@code product.categories}. {@code specifications} is stored as a JSON string, not a
 * {@code Map} field - the Map<->JSON boundary is Jackson, and Jackson is deliberately kept out of
 * this class; the conversion lives in {@code ProductCatalogService} instead (see its own javadoc).
 * {@code lastKnownQuantity} is denormalized, non-authoritative inventory state - the builder and
 * {@link #update} deliberately do not touch it; {@link #applyInventorySignal} is the only mutator
 * that ever writes to it.
 *
 * <p>EQUALITY IS KEYED ON {@code id} ONLY, via {@code @EqualsAndHashCode(onlyExplicitlyIncluded =
 * true)} - the standard JPA+Lombok pattern, not {@code Objects.hash(id)} over every field. Note the
 * one accepted caveat that comes with it: {@code hashCode()} is {@code id}-derived, so it changes
 * when a transient instance is first flushed - do not put a not-yet-persisted {@code Product} into
 * a {@code HashSet}/{@code HashMap} and persist it afterward. No code in this service does that
 * today. Construction goes through Lombok's generated builder, never a hand-rolled constructor.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Product {

  @EqualsAndHashCode.Include
  @ToString.Include
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** Merchandising identifier. Unique, and the natural key external systems quote. */
  @ToString.Include
  @Column(name = "sku", nullable = false, length = 64, unique = true)
  private String sku;

  @Column(name = "name", nullable = false, length = 200)
  private String name;

  /**
   * Scale 2 in the database. Declared here without {@code precision}/{@code scale} attributes on
   * purpose: those only affect DDL generation, and Flyway - not Hibernate - owns this schema.
   */
  @Column(name = "price_amount", nullable = false)
  private BigDecimal priceAmount;

  /** ISO-4217 alphabetic code. {@code char(3)} in the database, so always exactly three chars. */
  @Column(name = "price_currency", nullable = false, length = 3)
  private String priceCurrency;

  @ToString.Include
  @Column(name = "active", nullable = false)
  private boolean active;

  /** Nullable FK -> {@code product.categories.id}. No {@code @ManyToOne} - see class javadoc. */
  @Column(name = "category_id")
  private UUID categoryId;

  /** Nullable, free-text seller attribution. No sellers table, no FK. */
  @Column(name = "seller_name", length = 200)
  private String sellerName;

  /**
   * Nullable. Stored as a JSON string; the {@code Map<String,Object>} <-> {@code String} boundary
   * is Jackson, and Jackson lives in {@code ProductCatalogService}, not here - see class javadoc.
   *
   * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} is required alongside {@code columnDefinition}: without
   * it, Hibernate binds this field as a plain {@code varchar} parameter, which Postgres refuses to
   * implicitly cast to the column's real {@code jsonb} type.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "specifications", columnDefinition = "jsonb")
  private String specifications;

  /**
   * Nullable, denormalized, display-only, non-authoritative snapshot of inventory quantity.
   * Populated ONLY by {@link #applyInventorySignal}; never touched by {@link #create} or {@link
   * #update}.
   */
  @Column(name = "last_known_quantity")
  private Integer lastKnownQuantity;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /**
   * Optimistic-concurrency token. Checked explicitly by the service before any write is applied.
   */
  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  /** Nullable, long-form product description. */
  @Column(name = "description", columnDefinition = "text")
  private String description;

  /** Nullable, list price before discount. */
  @Column(name = "list_price")
  private BigDecimal listPrice;

  /** Nullable, sale/current price. */
  @Column(name = "sale_price")
  private BigDecimal salePrice;

  /** Nullable, discount percentage (0-100). */
  @Column(name = "discount_percent")
  private BigDecimal discountPercent;

  /** Nullable, primary product image thumbnail URL. */
  @Column(name = "thumbnail_url", length = 2048)
  private String thumbnailUrl;

  /**
   * Nullable, array of video URLs stored as JSONB. Conversion handled at service layer via Jackson.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "video_urls", columnDefinition = "jsonb")
  private String videoUrls;

  /** Nullable, 360-degree view URL. */
  @Column(name = "view_360_url", length = 2048)
  private String view360Url;

  /** Nullable, weight in grams. */
  @Column(name = "weight_grams")
  private Integer weightGrams;

  /** Nullable, length in cm. */
  @Column(name = "dimensions_length")
  private BigDecimal dimensionsLength;

  /** Nullable, width in cm. */
  @Column(name = "dimensions_width")
  private BigDecimal dimensionsWidth;

  /** Nullable, height in cm. */
  @Column(name = "dimensions_height")
  private BigDecimal dimensionsHeight;

  /** Nullable, primary product color. */
  @Column(name = "color", length = 100)
  private String color;

  /** Nullable, primary material composition. */
  @Column(name = "material", length = 100)
  private String material;

  /**
   * Nullable, denormalized average rating (0-5). Updated only by the rating-service listener when
   * ratings are added/updated - never touched by {@link #create} or {@link #update}.
   */
  @Column(name = "average_rating")
  private BigDecimal averageRating;

  /** Denormalized count of ratings. Updated by rating-service listener. Defaults to 0. */
  @Builder.Default
  @Column(name = "rating_count", nullable = false)
  private int ratingCount = 0;

  /** Denormalized count of reviews (review_text not null). Updated by rating-service listener. */
  @Builder.Default
  @Column(name = "review_count", nullable = false)
  private int reviewCount = 0;

  /**
   * Nullable, rating distribution as JSONB, e.g. {1: 10, 2: 20, 3: 50, 4: 100, 5: 200}. Updated by
   * rating-service listener.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "rating_distribution", columnDefinition = "jsonb")
  private String ratingDistribution;

  /** Flag for featured/promoted products. Defaults to false. */
  @Builder.Default
  @Column(name = "is_featured", nullable = false)
  private boolean isFeatured = false;

  /** Nullable, seller's own rating (separate from product ratings). */
  @Column(name = "seller_rating")
  private BigDecimal sellerRating;

  /** Flag indicating seller is verified. Defaults to false. */
  @Builder.Default
  @Column(name = "is_seller_verified", nullable = false)
  private boolean isSellerVerified = false;

  /** Nullable brand (facet). */
  @Column(name = "brand", length = 120)
  private String brand;

  /** Nullable filterable key/values as JSON (e.g. {"RAM":"8 GB"}). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "attributes", columnDefinition = "jsonb")
  private String attributes;

  /** URL slug; unique. Set on create; a DB trigger covers raw inserts. */
  @Column(name = "slug", length = 240)
  private String slug;

  /** Owning seller (platform seller for catalog products). */
  @Builder.Default
  @Column(name = "seller_id", nullable = false)
  private UUID sellerId = UUID.fromString("00000000-0000-0000-0000-00000000a11e");

  /** PLATFORM or SELLER. */
  @Builder.Default
  @Column(name = "listed_by", nullable = false, length = 20)
  private String listedBy = "PLATFORM";

  /**
   * Applies a denormalized inventory signal, deliberately narrow - cannot re-trigger {@code
   * product.events} publication. This method has no access to {@code ProductEventPublisher} at all
   * (this class has zero Kafka/event dependencies, same as today), so there is no code path by
   * which calling it could publish anything - the domain-layer half of the "no ping-pong" guarantee
   * (docs/LLD-catalog-enrichment-and-saga.md S2.1). Called ONLY from {@code
   * ProductCatalogService.applyInventorySignal}, in reaction to a consumed {@code
   * INVENTORY_QUANTITY_CHANGED} event - never by {@link #create}/{@link #update}.
   *
   * @param quantity the quantity on hand at the inventory-side publish time
   * @param now the current instant, supplied by the caller's {@code TimeProvider}
   */
  public void applyInventorySignal(Integer quantity, Instant now) {
    this.lastKnownQuantity = quantity;
    this.updatedAt = now;
  }
}
