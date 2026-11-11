package com.acme.ecommerce.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A catalog category, one level of subcategory deep.
 *
 * <p>{@code parent_category_id} is a PLAIN {@code UUID} COLUMN, not a {@code @ManyToOne}
 * self-association - the same "no associations" reason {@link Product} avoids one: a mapped
 * relationship invites an accidental fetch, and this class has no read path that needs the parent
 * loaded eagerly. Depth enforcement (rejecting a category whose own parent already has a parent) is
 * NOT a domain invariant here - checking it needs a repository lookup this entity has no access to
 * - it lives in the service layer that has one (see docs/HLD-catalog-enrichment-and-saga.md S11
 * Open Question #5).
 *
 * <p>NO {@code @PrePersist}/{@code @PreUpdate}, AND NO {@code Instant.now()} ANYWHERE IN THIS
 * CLASS, mirroring {@link Product}: {@code now} is an explicit parameter callers pass to {@link
 * #builder()} and to {@link #rename(String, Instant)}.
 *
 * <p>EQUALITY IS KEYED ON {@code id} ONLY (same pattern, same caveat, as {@link Product}).
 * Construction goes through Lombok's generated builder, never a hand-rolled constructor.
 */
@Entity
@Table(name = "categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Category {

  @EqualsAndHashCode.Include
  @ToString.Include
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ToString.Include
  @Column(name = "name", nullable = false, length = 120)
  private String name;

  /** Nullable, long-form category description. */
  @Column(name = "description", columnDefinition = "text")
  private String description;

  /** Self-referencing FK. {@code null} for a top-level category. */
  @ToString.Include
  @Column(name = "parent_category_id")
  private UUID parentCategoryId;

  /**
   * Nesting depth: 0 for root categories, 1 for children of root. Defaults to 0. Enforced at
   * application layer to max 1 (no 3+ levels).
   */
  @Builder.Default
  @Column(name = "depth_level", nullable = false)
  private int depthLevel = 0;

  /**
   * Flag indicating if category is active/visible. Defaults to true. Not enforced in the database,
   * only in application queries.
   */
  @Builder.Default
  @Column(name = "is_active", nullable = false)
  private boolean isActive = true;

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

  /**
   * Renames a category.
   *
   * @param name the new display name
   * @param now the current instant, supplied by the caller's {@code TimeProvider}
   */
  public void rename(String name, Instant now) {
    this.name = Objects.requireNonNull(name, "name");
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  // Setters for properties that may be updated by CategoryService
  public void setName(String name) {
    this.name = name;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public void setIsActive(boolean isActive) {
    this.isActive = isActive;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
