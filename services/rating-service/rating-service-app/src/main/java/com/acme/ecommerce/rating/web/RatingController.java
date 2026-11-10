package com.acme.ecommerce.rating.web;

import com.acme.ecommerce.rating.api.dto.RatingResponse;
import com.acme.ecommerce.rating.api.dto.RatingSummaryResponse;
import com.acme.ecommerce.rating.service.RatingService;
import com.acme.ecommerce.rating.web.dto.CreateRatingRequest;
import com.acme.ecommerce.rating.web.dto.UpdateRatingRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP edge of rating-service.
 *
 * <p>Thin: bind, delegate, return. No branching, no persistence. {@code ResourceNotFoundException}
 * / {@code ResourceConflictException} propagate to common-web's {@code GlobalExceptionHandler}.
 *
 * <p>{@code @PreAuthorize} ON WRITES: authenticated on POST (any user can rate), author-or-admin
 * on PUT/DELETE. Reads are public (no @PreAuthorize).
 */
@RestController
@RequestMapping("/api/ratings")
@RequiredArgsConstructor
public class RatingController {

  private static final int DEFAULT_PAGE_SIZE = 20;

  private final RatingService ratings;

  /**
   * Paged list of ratings for a product.
   *
   * @param productId the product
   * @param pageable page number/size/sort
   * @return paged result
   */
  @GetMapping("/products/{productId}")
  public PagedModel<RatingResponse> findByProductId(
      @PathVariable UUID productId,
      @PageableDefault(size = DEFAULT_PAGE_SIZE, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    return new PagedModel<>(ratings.findByProductId(productId, pageable));
  }

  /**
   * Summary statistics for a product's ratings.
   *
   * @param productId the product
   * @return the summary
   */
  @GetMapping("/products/{productId}/summary")
  public RatingSummaryResponse getSummary(@PathVariable UUID productId) {
    return ratings.getSummary(productId);
  }

  /**
   * One rating by id.
   *
   * @param id the rating identifier
   * @return the rating
   */
  @GetMapping("/{id}")
  public RatingResponse findById(@PathVariable UUID id) {
    return ratings.findById(id);
  }

  /**
   * Creates a new rating (authenticated).
   *
   * @param productId the product being rated
   * @param request the rating details
   * @param jwt the authenticated user's JWT token
   * @return the created rating
   */
  @PostMapping("/products/{productId}")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("isAuthenticated()")
  public RatingResponse create(
      @PathVariable UUID productId,
      @Valid @RequestBody CreateRatingRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    UUID userId = UUID.fromString(jwt.getSubject());
    return ratings.create(productId, request, userId);
  }

  /**
   * Updates a rating (author or admin only).
   *
   * @param id the rating identifier
   * @param request the new values
   * @param jwt the authenticated user's JWT token
   * @return the updated rating
   */
  @PutMapping("/{id}")
  @PreAuthorize("isAuthenticated()")
  public RatingResponse update(
      @PathVariable UUID id,
      @Valid @RequestBody UpdateRatingRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    UUID userId = UUID.fromString(jwt.getSubject());
    boolean isAdmin = jwt.getClaimAsStringList("scope").contains("ROLE_ADMIN");
    return ratings.update(id, request, userId, isAdmin);
  }

  /**
   * Deletes a rating (author or admin only).
   *
   * @param id the rating identifier
   * @param jwt the authenticated user's JWT token
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    UUID userId = UUID.fromString(jwt.getSubject());
    boolean isAdmin = jwt.getClaimAsStringList("scope").contains("ROLE_ADMIN");
    ratings.delete(id, userId, isAdmin);
  }

  /**
   * Adds a helpful/unhelpful vote on a rating (authenticated).
   *
   * @param ratingId the rating being voted on
   * @param voteType HELPFUL or UNHELPFUL
   * @param jwt the authenticated user's JWT token
   */
  @PostMapping("/{ratingId}/votes/{voteType}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void addVote(
      @PathVariable UUID ratingId,
      @PathVariable String voteType,
      @AuthenticationPrincipal Jwt jwt) {
    UUID userId = UUID.fromString(jwt.getSubject());
    ratings.addVote(ratingId, voteType, userId);
  }
}
