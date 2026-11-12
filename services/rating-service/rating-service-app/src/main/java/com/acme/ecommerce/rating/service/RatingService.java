package com.acme.ecommerce.rating.service;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.core.error.ForbiddenException;
import com.acme.ecommerce.common.core.error.ResourceConflictException;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.rating.api.dto.RatingMediaResponse;
import com.acme.ecommerce.rating.api.dto.RatingResponse;
import com.acme.ecommerce.rating.api.dto.RatingSummaryResponse;
import com.acme.ecommerce.rating.domain.Rating;
import com.acme.ecommerce.rating.domain.RatingVote;
import com.acme.ecommerce.rating.domain.ReviewMedia;
import com.acme.ecommerce.rating.event.RatingEventPublisher;
import com.acme.ecommerce.rating.event.RatingEventType;
import com.acme.ecommerce.rating.repository.RatingRepository;
import com.acme.ecommerce.rating.repository.RatingVoteRepository;
import com.acme.ecommerce.rating.web.dto.CreateRatingRequest;
import com.acme.ecommerce.rating.web.dto.UpdateRatingRequest;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Review persistence and rules. Purchase/display-name checks happen BEFORE the transactional calls
 * here (see {@link ReviewEligibilityService}); this class only touches the database.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RatingService {

  private static final String HELPFUL = "HELPFUL";
  private static final String UNHELPFUL = "UNHELPFUL";

  private final RatingRepository ratings;
  private final RatingVoteRepository votes;
  private final TimeProvider timeProvider;
  private final RatingEventPublisher events;
  private final ReviewMediaService mediaService;

  /** Public review list; stars / verified / withMedia are optional and AND-ed. */
  public Page<RatingResponse> findByProductId(
      UUID productId, Integer stars, boolean verified, boolean withMedia, Pageable pageable) {
    Specification<Rating> spec =
        (root, query, cb) -> {
          List<Predicate> ps = new ArrayList<>();
          ps.add(cb.equal(root.get("productId"), productId));
          if (stars != null) {
            ps.add(cb.equal(root.get("ratingValue"), stars));
          }
          if (verified) {
            ps.add(cb.isTrue(root.get("isVerifiedPurchase")));
          }
          if (withMedia) {
            Subquery<UUID> sq = query.subquery(UUID.class);
            Root<ReviewMedia> m = sq.from(ReviewMedia.class);
            sq.select(m.get("ratingId")).where(cb.equal(m.get("ratingId"), root.get("id")));
            ps.add(cb.exists(sq));
          }
          return cb.and(ps.toArray(new Predicate[0]));
        };
    return withMedia(ratings.findAll(spec, pageable).map(RatingService::toResponse));
  }

  public RatingResponse findById(UUID id) {
    return withMedia(toResponse(load(id)));
  }

  /** Attaches photos to every response of a page with a single query. */
  private Page<RatingResponse> withMedia(Page<RatingResponse> page) {
    Map<UUID, List<RatingMediaResponse>> byRating =
        mediaService.forRatings(page.getContent().stream().map(RatingResponse::getId).toList());
    page.getContent()
        .forEach(r -> r.setMedia(new ArrayList<>(byRating.getOrDefault(r.getId(), List.of()))));
    return page;
  }

  private RatingResponse withMedia(RatingResponse r) {
    r.setMedia(
        new ArrayList<>(
            mediaService.forRatings(List.of(r.getId())).getOrDefault(r.getId(), List.of())));
    return r;
  }

  /**
   * Back-office moderation search across all products. Filters are optional and AND-ed; {@code q}
   * matches title, text or reviewer display name (case-insensitive, LIKE wildcards stripped).
   */
  public Page<RatingResponse> adminSearch(
      UUID productId, Integer ratingValue, String term, Pageable pageable) {
    String q =
        term == null ? "" : term.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[%_\\\\]", "");
    Specification<Rating> spec =
        (root, query, cb) -> {
          List<Predicate> ps = new ArrayList<>();
          if (productId != null) {
            ps.add(cb.equal(root.get("productId"), productId));
          }
          if (ratingValue != null) {
            ps.add(cb.equal(root.get("ratingValue"), ratingValue));
          }
          if (!q.isEmpty()) {
            String like = "%" + q + "%";
            ps.add(
                cb.or(
                    cb.like(cb.lower(root.get("reviewTitle")), like),
                    cb.like(cb.lower(root.get("reviewText")), like),
                    cb.like(cb.lower(root.get("userDisplayName")), like)));
          }
          return cb.and(ps.toArray(new Predicate[0]));
        };
    return withMedia(ratings.findAll(spec, pageable).map(RatingService::toResponse));
  }

  /** The caller's own review of a product, if any. */
  public Optional<RatingResponse> findMine(UUID productId, UUID userId) {
    return ratings
        .findByProductIdAndUserId(productId, userId)
        .map(RatingService::toResponse)
        .map(this::withMedia);
  }

  /**
   * Stores a verified-purchase review. The caller has ALREADY been verified as a buyer; the unique
   * (product, user) constraint is the race-proof backstop for "one review per user".
   */
  @Transactional
  public RatingResponse create(
      UUID productId, CreateRatingRequest request, UUID userId, String displayName) {
    Instant now = timeProvider.now();

    Rating rating =
        Rating.builder()
            .productId(productId)
            .userId(userId)
            .userDisplayName(displayName)
            .ratingValue(request.getRatingValue())
            .reviewTitle(clean(request.getReviewTitle()))
            .reviewText(clean(request.getReviewText()))
            .isVerifiedPurchase(true)
            .createdAt(now)
            .updatedAt(now)
            .build();

    Rating saved;
    try {
      saved = ratings.saveAndFlush(rating);
    } catch (DataIntegrityViolationException e) {
      throw new CodedException(
          ErrorCode.REVIEW_ALREADY_EXISTS, "You have already reviewed this product", e);
    }
    RatingResponse response = toResponse(saved);

    events.publishAfterCommit(RatingEventType.RATING_ADDED, saved.getId(), productId, response);
    log.info("Created rating ratingId={} productId={}", saved.getId(), productId);
    return response;
  }

  @Transactional
  public RatingResponse update(UUID id, UpdateRatingRequest request, UUID userId, boolean isAdmin) {
    Rating rating = load(id);

    if (!isAdmin && !Objects.equals(rating.getUserId(), userId)) {
      throw new ForbiddenException("Only the review author or an admin may edit this review");
    }
    if (!Objects.equals(rating.getVersion(), request.getVersion())) {
      throw new ResourceConflictException(
          "Review " + id + " has changed (version " + rating.getVersion() + ")");
    }

    Instant now = timeProvider.now();
    if (request.getRatingValue() != null) {
      rating.updateRating(request.getRatingValue(), now);
    }
    rating.updateReview(clean(request.getReviewTitle()), clean(request.getReviewText()), now);

    Rating saved = ratings.saveAndFlush(rating);
    RatingResponse response = withMedia(toResponse(saved));

    events.publishAfterCommit(RatingEventType.RATING_UPDATED, id, rating.getProductId(), response);
    log.info("Updated rating ratingId={} productId={}", id, rating.getProductId());
    return response;
  }

  @Transactional
  public void delete(UUID id, UUID userId, boolean isAdmin) {
    Rating rating = load(id);

    if (!isAdmin && !Objects.equals(rating.getUserId(), userId)) {
      throw new ForbiddenException("Only the review author or an admin may delete this review");
    }

    UUID productId = rating.getProductId();
    mediaService.deleteFilesOf(id);
    ratings.delete(rating);

    events.publishAfterCommit(RatingEventType.RATING_DELETED, id, productId, null);
    log.info("Deleted rating ratingId={} productId={}", id, productId);
  }

  public RatingSummaryResponse getSummary(UUID productId) {
    // All five keys are always present so the client never has to guess a missing star.
    Map<Integer, Integer> distribution = new TreeMap<>();
    for (int star = 1; star <= 5; star++) {
      distribution.put(star, 0);
    }
    long total = 0;
    long sum = 0;
    for (Object[] row : ratings.countByRatingValue(productId)) {
      int star = ((Number) row[0]).intValue();
      int count = ((Number) row[1]).intValue();
      distribution.put(star, count);
      total += count;
      sum += (long) star * count;
    }
    BigDecimal average =
        total == 0
            ? BigDecimal.ZERO.setScale(2)
            : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);

    return new RatingSummaryResponse(
        productId, average, (int) total, ratings.countReviewsByProductId(productId), distribution);
  }

  /**
   * Records or switches the caller's helpfulness vote. Authors cannot vote on their own review.
   * Counters are recomputed from the votes table so they cannot drift.
   */
  @Transactional
  public void addVote(UUID ratingId, String voteType, UUID userId) {
    if (!HELPFUL.equals(voteType) && !UNHELPFUL.equals(voteType)) {
      throw ValidationException.forField("voteType", "must be HELPFUL or UNHELPFUL");
    }
    Rating rating = load(ratingId);
    if (Objects.equals(rating.getUserId(), userId)) {
      throw new CodedException(
          ErrorCode.CANNOT_VOTE_OWN_REVIEW, "You cannot vote on your own review");
    }

    Instant now = timeProvider.now();
    Optional<RatingVote> existing = votes.findByRatingIdAndUserId(ratingId, userId);
    if (existing.isPresent() && existing.get().getVoteType().equals(voteType)) {
      return;
    }
    existing.ifPresent(votes::delete);
    votes.flush();
    votes.saveAndFlush(
        RatingVote.builder()
            .ratingId(ratingId)
            .userId(userId)
            .voteType(voteType)
            .createdAt(now)
            .build());
    ratings.recountVotes(ratingId);
    log.info("Recorded vote ratingId={} voteType={}", ratingId, voteType);
  }

  private Rating load(UUID id) {
    return ratings.findById(id).orElseThrow(() -> new ResourceNotFoundException("Rating", id));
  }

  /** Trims; blank becomes null so "no review text" is stored one way only. */
  private static String clean(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static RatingResponse toResponse(Rating rating) {
    return new RatingResponse(
        rating.getId(),
        rating.getProductId(),
        rating.getUserDisplayName(),
        rating.getRatingValue(),
        rating.getReviewTitle(),
        rating.getReviewText(),
        rating.isVerifiedPurchase(),
        rating.getHelpfulCount(),
        rating.getUnhelpfulCount(),
        rating.getVersion(),
        rating.getCreatedAt(),
        rating.getUpdatedAt(),
        new ArrayList<>());
  }
}
