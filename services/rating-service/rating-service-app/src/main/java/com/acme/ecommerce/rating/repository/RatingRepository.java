package com.acme.ecommerce.rating.repository;

import com.acme.ecommerce.rating.domain.Rating;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RatingRepository
    extends JpaRepository<Rating, UUID>, JpaSpecificationExecutor<Rating> {

  /** Sorting comes from the {@link Pageable}; the service only ever passes allow-listed sorts. */
  Page<Rating> findByProductId(UUID productId, Pageable pageable);

  Optional<Rating> findByProductIdAndUserId(UUID productId, UUID userId);

  boolean existsByProductIdAndUserId(UUID productId, UUID userId);

  long countByUserIdAndCreatedAtAfter(UUID userId, java.time.Instant after);

  long countByProductIdAndRatingValueAndCreatedAtAfter(
      UUID productId, int ratingValue, java.time.Instant after);

  /** Reviews (by anyone) whose text is a near-duplicate (trigram similarity > 0.9). */
  @Query(
      value =
          "SELECT COUNT(*) FROM rating.ratings WHERE review_text IS NOT NULL"
              + " AND rating.similarity(lower(review_text), lower(:text)) > 0.9",
      nativeQuery = true)
  long countSimilarText(@Param("text") String text);

  /** Rows of (ratingValue, count) for one product - the star distribution in one query. */
  @Query(
      "SELECT r.ratingValue, COUNT(r) FROM Rating r WHERE r.productId = :productId AND r.flagged = false"
          + " GROUP BY r.ratingValue")
  List<Object[]> countByRatingValue(@Param("productId") UUID productId);

  @Query(
      "SELECT COUNT(r) FROM Rating r WHERE r.productId = :productId AND r.reviewText IS NOT NULL"
          + " AND r.flagged = false")
  int countReviewsByProductId(@Param("productId") UUID productId);

  /**
   * Recomputes the helpful/unhelpful counters from the votes table. A bulk update on purpose: it
   * does not bump {@code @Version} or {@code updatedAt}, so another user's vote never invalidates
   * the author's pending edit.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE Rating r SET"
          + " r.helpfulCount = (SELECT COUNT(v) FROM RatingVote v"
          + " WHERE v.ratingId = r.id AND v.voteType = 'HELPFUL'),"
          + " r.unhelpfulCount = (SELECT COUNT(v) FROM RatingVote v"
          + " WHERE v.ratingId = r.id AND v.voteType = 'UNHELPFUL')"
          + " WHERE r.id = :ratingId")
  int recountVotes(@Param("ratingId") UUID ratingId);
}
