package com.acme.ecommerce.rating.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
 * A product rating/review.
 *
 * <p>NO ASSOCIATIONS, therefore NO N+1 RISK. Both {@code product_id} and {@code user_id} are PLAIN
 * {@code UUID} COLUMNS, NOT {@code @ManyToOne} associations - the same "no associations" invariant
 * {@link com.acme.ecommerce.product.domain.Product} follows.
 *
 * <p>NO {@code @PrePersist}/{@code @PreUpdate}, AND NO {@code Instant.now()} ANYWHERE IN THIS CLASS
 * - mirroring {@link com.acme.ecommerce.product.domain.Product}: {@code now} is an explicit
 * parameter callers pass to {@link #builder()} and to mutation methods like {@link
 * #updateReview(String, String, Instant)}.
 *
 * <p>{@code @Version} MIRRORS {@code Product.java}'s optimistic-concurrency pattern - checked
 * explicitly by the service before any write is applied.
 *
 * <p>RATING VALUE IS VALIDATED HERE (1-5 scale) via a {@code @Column} CHECK constraint and domain
 * logic, NOT left to the database alone.
 *
 * <p>EQUALITY IS KEYED ON {@code id} ONLY, via {@code @EqualsAndHashCode(onlyExplicitlyIncluded =
 * true)} - the standard JPA+Lombok pattern, not {@code Objects.hash(id)} over every field. Note the
 * one accepted caveat: {@code hashCode()} is {@code id}-derived, so it changes when a transient
 * instance is first flushed - do not put a not-yet-persisted {@code Rating} into a {@code
 * HashSet}/{@code HashMap} and persist it afterward. No code in this service does that today.
 * Construction goes through Lombok's generated builder, never a hand-rolled constructor.
 */
@Entity
@Table(name = "ratings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Rating {

  @EqualsAndHashCode.Include
  @ToString.Include
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** FK to product-service. Denormalized; no mapped JPA relationship. */
  @ToString.Include
  @Column(name = "product_id", nullable = false)
  private UUID productId;

  /** FK to auth-service users. The user who wrote this rating. */
  @Column(name = "user_id", nullable = false)
  private UUID userId;

  /** Denormalized display name of the rating author. Nullable if user was deleted. */
  @Column(name = "user_display_name", length = 200)
  private String userDisplayName;

  /** Rating value on a 1-5 scale. Domain validation ensures this. */
  @Column(name = "rating_value", nullable = false)
  private int ratingValue;

  /** Review title. Nullable if only a rating with no review text. */
  @Column(name = "review_title", length = 200)
  private String reviewTitle;

  /** Full review text. Nullable; a rating can exist without a review. */
  @Column(name = "review_text", columnDefinition = "text")
  private String reviewText;

  /** Flag indicating this is a verified purchase. Defaults to false. */
  @Builder.Default
  @Column(name = "is_verified_purchase", nullable = false)
  private boolean isVerifiedPurchase = false;

  /** Aggregate count of users who found this review helpful. Defaults to 0. */
  @Builder.Default
  @Column(name = "helpful_count", nullable = false)
  private int helpfulCount = 0;

  /** Aggregate count of users who marked it unhelpful. Defaults to 0. */
  @Builder.Default
  @Column(name = "unhelpful_count", nullable = false)
  private int unhelpfulCount = 0;

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
   * Validates and updates the rating value.
   *
   * @param newRatingValue the new rating (1-5)
   * @param now the current instant, supplied by the caller's {@code TimeProvider}
   * @throws IllegalArgumentException if rating is outside 1-5 range
   */
  public void updateRating(int newRatingValue, Instant now) {
    if (newRatingValue < 1 || newRatingValue > 5) {
      throw new IllegalArgumentException("Rating value must be between 1 and 5");
    }
    this.ratingValue = newRatingValue;
    this.updatedAt = now;
  }

  /**
   * Updates the review text and title.
   *
   * @param newTitle the new review title (nullable)
   * @param newText the new review text (nullable)
   * @param now the current instant, supplied by the caller's {@code TimeProvider}
   */
  public void updateReview(String newTitle, String newText, Instant now) {
    this.reviewTitle = newTitle;
    this.reviewText = newText;
    this.updatedAt = now;
  }

  /**
   * Increments the helpful count.
   *
   * @param now the current instant, supplied by the caller's {@code TimeProvider}
   */
  public void incrementHelpful(Instant now) {
    this.helpfulCount++;
    this.updatedAt = now;
  }

  /**
   * Decrements the helpful count (when a vote is changed or removed).
   *
   * @param now the current instant, supplied by the caller's {@code TimeProvider}
   */
  public void decrementHelpful(Instant now) {
    this.helpfulCount = Math.max(0, this.helpfulCount - 1);
    this.updatedAt = now;
  }

  /**
   * Increments the unhelpful count.
   *
   * @param now the current instant, supplied by the caller's {@code TimeProvider}
   */
  public void incrementUnhelpful(Instant now) {
    this.unhelpfulCount++;
    this.updatedAt = now;
  }

  /**
   * Decrements the unhelpful count (when a vote is changed or removed).
   *
   * @param now the current instant, supplied by the caller's {@code TimeProvider}
   */
  public void decrementUnhelpful(Instant now) {
    this.unhelpfulCount = Math.max(0, this.unhelpfulCount - 1);
    this.updatedAt = now;
  }
}
