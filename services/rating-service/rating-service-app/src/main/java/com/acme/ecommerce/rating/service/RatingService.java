package com.acme.ecommerce.rating.service;

import com.acme.ecommerce.common.core.error.ResourceConflictException;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.rating.api.dto.RatingResponse;
import com.acme.ecommerce.rating.api.dto.RatingSummaryResponse;
import com.acme.ecommerce.rating.domain.Rating;
import com.acme.ecommerce.rating.domain.RatingVote;
import com.acme.ecommerce.rating.repository.RatingRepository;
import com.acme.ecommerce.rating.repository.RatingVoteRepository;
import com.acme.ecommerce.rating.event.RatingEventPublisher;
import com.acme.ecommerce.rating.event.RatingEventType;
import com.acme.ecommerce.rating.web.dto.CreateRatingRequest;
import com.acme.ecommerce.rating.web.dto.UpdateRatingRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for product ratings and reviews.
 *
 * <p>Thin on the read side. Writes enforce RBAC (author-only or admin for updates/deletes) and
 * publish Kafka events after commit (via {@link RatingEventPublisher}).
 *
 * <p>{@code readOnly = true} AT THE CLASS LEVEL lets Hibernate skip dirty-checking on reads and
 * tells JDBC those transactions will not write. Each write method opens its own {@code
 * @Transactional}, overriding this default.
 *
 * <p>HELPFUL/UNHELPFUL VOTING IS IDEMPOTENT via the UNIQUE(rating_id, user_id) constraint: a user
 * can change their vote (helpful -> unhelpful) by issuing the same request twice with a different
 * vote type - an UPSERT behaviour that avoids client-side deduplication logic.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RatingService {

  private final RatingRepository ratings;
  private final RatingVoteRepository votes;
  private final TimeProvider timeProvider;
  private final RatingEventPublisher events;

  /**
   * Paged list of ratings for a product, ordered by creation (newest first).
   *
   * @param productId the product
   * @param pageable page number/size/sort
   * @return paged result
   */
  public Page<RatingResponse> findByProductId(UUID productId, Pageable pageable) {
    return ratings
        .findByProductIdOrderByCreatedAtDesc(productId, pageable)
        .map(this::toResponse);
  }

  /**
   * Gets a single rating by id.
   *
   * @param id the rating identifier
   * @return the rating
   * @throws ResourceNotFoundException when the rating does not exist
   */
  public RatingResponse findById(UUID id) {
    Rating rating =
        ratings.findById(id).orElseThrow(() -> new ResourceNotFoundException("Rating", id));
    return toResponse(rating);
  }

  /**
   * Creates a new rating.
   *
   * @param productId the product being rated
   * @param request the rating details
   * @param userId the user creating the rating
   * @return the created rating
   */
  @Transactional
  public RatingResponse create(UUID productId, CreateRatingRequest request, UUID userId) {
    Instant now = timeProvider.now();

    if (request.ratingValue() < 1 || request.ratingValue() > 5) {
      throw new IllegalArgumentException("Rating value must be between 1 and 5");
    }

    Rating rating =
        Rating.builder()
            .productId(productId)
            .userId(userId)
            .ratingValue(request.ratingValue())
            .reviewTitle(request.reviewTitle())
            .reviewText(request.reviewText())
            .isVerifiedPurchase(request.isVerifiedPurchase() != null && request.isVerifiedPurchase())
            .createdAt(now)
            .updatedAt(now)
            .build();

    Rating saved = ratings.save(rating);
    RatingResponse response = toResponse(saved);

    events.publishAfterCommit(RatingEventType.RATING_ADDED, saved.getId(), productId, response);

    log.info("Created rating ratingId={} productId={} userId={}", saved.getId(), productId, userId);

    return response;
  }

  /**
   * Updates an existing rating. Only the author or an admin may update.
   *
   * @param id the rating identifier
   * @param request the new values
   * @param userId the user making the update
   * @param isAdmin true if caller is admin
   * @return the updated rating
   * @throws ResourceNotFoundException when the rating does not exist
   * @throws ResourceConflictException when version is stale or RBAC fails
   */
  @Transactional
  public RatingResponse update(UUID id, UpdateRatingRequest request, UUID userId, boolean isAdmin) {

    Rating rating = ratings.findById(id).orElseThrow(() -> new ResourceNotFoundException("Rating", id));

    if (!isAdmin && !Objects.equals(rating.getUserId(), userId)) {
      throw new ResourceConflictException("Only the rating author or admin may update this rating");
    }

    if (!Objects.equals(rating.getVersion(), request.version())) {
      throw new ResourceConflictException(
          "Rating " + id + " has version " + rating.getVersion() + ", not " + request.version());
    }

    Instant now = timeProvider.now();

    if (request.ratingValue() != null) {
      if (request.ratingValue() < 1 || request.ratingValue() > 5) {
        throw new IllegalArgumentException("Rating value must be between 1 and 5");
      }
      rating.updateRating(request.ratingValue(), now);
    }

    rating.updateReview(request.reviewTitle(), request.reviewText(), now);

    Rating saved = ratings.saveAndFlush(rating);
    RatingResponse response = toResponse(saved);

    events.publishAfterCommit(RatingEventType.RATING_UPDATED, id, rating.getProductId(), response);

    log.info("Updated rating ratingId={} productId={}", id, rating.getProductId());

    return response;
  }

  /**
   * Deletes a rating. Only the author or an admin may delete.
   *
   * @param id the rating identifier
   * @param userId the user making the deletion
   * @param isAdmin true if caller is admin
   * @throws ResourceNotFoundException when the rating does not exist
   * @throws ResourceConflictException when RBAC fails
   */
  @Transactional
  public void delete(UUID id, UUID userId, boolean isAdmin) {

    Rating rating = ratings.findById(id).orElseThrow(() -> new ResourceNotFoundException("Rating", id));

    if (!isAdmin && !Objects.equals(rating.getUserId(), userId)) {
      throw new ResourceConflictException("Only the rating author or admin may delete this rating");
    }

    UUID productId = rating.getProductId();
    ratings.delete(rating);

    events.publishAfterCommit(RatingEventType.RATING_DELETED, id, productId, null);

    log.info("Deleted rating ratingId={} productId={}", id, productId);
  }

  /**
   * Gets summary statistics for a product's ratings.
   *
   * @param productId the product
   * @return the summary
   */
  public RatingSummaryResponse getSummary(UUID productId) {
    int ratingCount = ratings.countByProductId(productId);
    int reviewCount = ratings.countReviewsByProductId(productId);
    Double avgRating = ratings.getAverageRatingByProductId(productId);

    Map<Integer, Integer> distribution = buildRatingDistribution(productId);

    return new RatingSummaryResponse(
        productId,
        avgRating != null ? BigDecimal.valueOf(avgRating) : null,
        ratingCount,
        reviewCount,
        distribution);
  }

  /**
   * Adds or updates a helpful/unhelpful vote on a rating (idempotent).
   *
   * @param ratingId the rating being voted on
   * @param voteType HELPFUL or UNHELPFUL
   * @param userId the user casting the vote
   */
  @Transactional
  public void addVote(UUID ratingId, String voteType, UUID userId) {

    Rating rating =
        ratings.findById(ratingId).orElseThrow(() -> new ResourceNotFoundException("Rating", ratingId));

    Instant now = timeProvider.now();

    RatingVote existingVote = votes.findByRatingIdAndUserId(ratingId, userId).orElse(null);

    if (existingVote != null) {
      String oldVoteType = existingVote.getVoteType();
      if (!Objects.equals(oldVoteType, voteType)) {
        if ("HELPFUL".equals(oldVoteType)) {
          rating.decrementHelpful(now);
        } else if ("UNHELPFUL".equals(oldVoteType)) {
          rating.decrementUnhelpful(now);
        }

        votes.delete(existingVote);

        if ("HELPFUL".equals(voteType)) {
          rating.incrementHelpful(now);
        } else if ("UNHELPFUL".equals(voteType)) {
          rating.incrementUnhelpful(now);
        }

        RatingVote newVote =
            RatingVote.builder()
                .ratingId(ratingId)
                .userId(userId)
                .voteType(voteType)
                .createdAt(now)
                .build();
        votes.save(newVote);
      }
    } else {
      RatingVote newVote =
          RatingVote.builder()
              .ratingId(ratingId)
              .userId(userId)
              .voteType(voteType)
              .createdAt(now)
              .build();
      votes.save(newVote);

      if ("HELPFUL".equals(voteType)) {
        rating.incrementHelpful(now);
      } else if ("UNHELPFUL".equals(voteType)) {
        rating.incrementUnhelpful(now);
      }
    }

    ratings.saveAndFlush(rating);
    log.info("Added vote ratingId={} userId={} voteType={}", ratingId, userId, voteType);
  }

  private RatingResponse toResponse(Rating rating) {
    return new RatingResponse(
        rating.getId(),
        rating.getProductId(),
        rating.getUserId(),
        rating.getUserDisplayName(),
        rating.getRatingValue(),
        rating.getReviewTitle(),
        rating.getReviewText(),
        rating.isVerifiedPurchase(),
        rating.getHelpfulCount(),
        rating.getUnhelpfulCount(),
        rating.getVersion(),
        rating.getCreatedAt(),
        rating.getUpdatedAt());
  }

  private Map<Integer, Integer> buildRatingDistribution(UUID productId) {
    Map<Integer, Integer> distribution = new HashMap<>();
    for (int star = 1; star <= 5; star++) {
      Page<Rating> ratingsByValue =
          ratings.findByProductIdAndRatingValueGreaterThanEqualOrderByCreatedAtDesc(
              productId, star, Pageable.unpaged());
      long count = ratingsByValue.getContent().stream()
          .filter(r -> r.getRatingValue() == star)
          .count();
      if (count > 0) {
        distribution.put(star, (int) count);
      }
    }
    return distribution;
  }
}
