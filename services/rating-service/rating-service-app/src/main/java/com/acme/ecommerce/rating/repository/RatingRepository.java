package com.acme.ecommerce.rating.repository;

import com.acme.ecommerce.rating.domain.Rating;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link Rating}s.
 *
 * <p>Custom queries support the filtering/search paths Phase 5 exposes (rating value, verified
 * purchase, helpful votes, etc.).
 */
@Repository
public interface RatingRepository extends JpaRepository<Rating, UUID> {

  /**
   * All ratings for a product, ordered by creation (newest first).
   *
   * @param productId the product
   * @param pageable page number/size/sort
   * @return paged result
   */
  Page<Rating> findByProductIdOrderByCreatedAtDesc(UUID productId, Pageable pageable);

  /**
   * Ratings for a product with a minimum star value.
   *
   * @param productId the product
   * @param minRating minimum rating (1-5)
   * @param pageable page number/size/sort
   * @return paged result
   */
  Page<Rating> findByProductIdAndRatingValueGreaterThanEqualOrderByCreatedAtDesc(
      UUID productId, int minRating, Pageable pageable);

  /**
   * Verified-purchase ratings for a product.
   *
   * @param productId the product
   * @param pageable page number/size/sort
   * @return paged result
   */
  Page<Rating> findByProductIdAndIsVerifiedPurchaseTrueOrderByCreatedAtDesc(
      UUID productId, Pageable pageable);

  /**
   * Ratings with review text (reviews, not bare ratings).
   *
   * @param productId the product
   * @param pageable page number/size/sort
   * @return paged result
   */
  @Query("SELECT r FROM Rating r WHERE r.productId = ?1 AND r.reviewText IS NOT NULL ORDER BY r.createdAt DESC")
  Page<Rating> findReviewsByProductId(UUID productId, Pageable pageable);

  /**
   * Count of ratings for a product.
   *
   * @param productId the product
   * @return count
   */
  int countByProductId(UUID productId);

  /**
   * Count of ratings for a product with review text.
   *
   * @param productId the product
   * @return count
   */
  @Query("SELECT COUNT(r) FROM Rating r WHERE r.productId = ?1 AND r.reviewText IS NOT NULL")
  int countReviewsByProductId(UUID productId);

  /**
   * Average rating for a product.
   *
   * @param productId the product
   * @return average rating, or null if no ratings
   */
  @Query("SELECT AVG(r.ratingValue) FROM Rating r WHERE r.productId = ?1")
  Double getAverageRatingByProductId(UUID productId);

  /**
   * Ratings by a specific user.
   *
   * @param userId the user
   * @param pageable page number/size/sort
   * @return paged result
   */
  Page<Rating> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
