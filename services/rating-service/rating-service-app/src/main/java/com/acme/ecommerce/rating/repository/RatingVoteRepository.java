package com.acme.ecommerce.rating.repository;

import com.acme.ecommerce.rating.domain.RatingVote;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link RatingVote}s.
 *
 * <p>UNIQUE constraint on (rating_id, user_id) makes vote addition idempotent: a user can change
 * their vote but only one vote per rating per user can exist.
 */
@Repository
public interface RatingVoteRepository extends JpaRepository<RatingVote, UUID> {

  /**
   * Looks up a user's vote on a rating.
   *
   * @param ratingId the rating being voted on
   * @param userId the user casting the vote
   * @return the vote if it exists, or empty
   */
  Optional<RatingVote> findByRatingIdAndUserId(UUID ratingId, UUID userId);

  /**
   * Checks if a user has voted on a rating.
   *
   * @param ratingId the rating
   * @param userId the user
   * @return true if the user has voted
   */
  boolean existsByRatingIdAndUserId(UUID ratingId, UUID userId);

  /**
   * Deletes all votes on a rating (called when a rating is deleted, cascaded at DB level).
   *
   * @param ratingId the rating
   */
  void deleteByRatingId(UUID ratingId);
}
