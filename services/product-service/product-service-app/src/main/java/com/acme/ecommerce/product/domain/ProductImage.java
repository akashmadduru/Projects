package com.acme.ecommerce.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One image belonging to a {@link Product}.
 *
 * <p>{@code product_id} is a PLAIN {@code UUID} COLUMN, not a mapped association back to {@link
 * Product} - keeping {@code Product}'s own "NO ASSOCIATIONS" invariant intact from both directions.
 * The FK is enforced at the database level instead (same-database, {@code ON DELETE CASCADE}), so
 * an orphaned image row cannot outlive its product without a mapped JPA relationship being involved
 * at all.
 *
 * <p>NO {@code @PrePersist}/{@code @PreUpdate}, mirroring {@link Product}: {@code now} is an
 * explicit parameter callers pass to {@link #builder()}.
 *
 * <p>EQUALITY IS KEYED ON {@code id} ONLY (same pattern, same caveat, as {@link Product}).
 * Construction goes through Lombok's generated builder, never a hand-rolled constructor.
 */
@Entity
@Table(name = "product_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class ProductImage {

  @EqualsAndHashCode.Include
  @ToString.Include
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** FK -> {@code product.products.id}, {@code ON DELETE CASCADE} at the database level. */
  @ToString.Include
  @Column(name = "product_id", nullable = false, updatable = false)
  private UUID productId;

  @Column(name = "url", nullable = false, length = 2048)
  private String url;

  @ToString.Include
  @Column(name = "display_order", nullable = false)
  private int displayOrder;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
