package com.acme.ecommerce.rating.web;

import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.common.security.Roles;
import com.acme.ecommerce.rating.api.dto.MyRatingStatusResponse;
import com.acme.ecommerce.rating.api.dto.RatingResponse;
import com.acme.ecommerce.rating.api.dto.RatingSummaryResponse;
import com.acme.ecommerce.rating.service.RatingService;
import com.acme.ecommerce.rating.service.ReviewEligibilityService;
import com.acme.ecommerce.rating.web.dto.CreateRatingRequest;
import com.acme.ecommerce.rating.web.dto.UpdateRatingRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reviews for purchased products. The user id is ALWAYS the JWT subject, never a request field. */
@RestController
@RequestMapping("/api/ratings")
@RequiredArgsConstructor
public class RatingController {

  private static final int DEFAULT_PAGE_SIZE = 10;
  private static final int MAX_PAGE_SIZE = 50;

  /** Allow-listed public sorts: the request value maps to a fixed (property, direction) pair. */
  private static final Map<String, Sort> SORTS =
      Map.of(
          "createdAt,desc", Sort.by(Sort.Order.desc("createdAt")),
          "helpfulCount,desc", Sort.by(Sort.Order.desc("helpfulCount")),
          "ratingValue,desc", Sort.by(Sort.Order.desc("ratingValue")),
          "ratingValue,asc", Sort.by(Sort.Order.asc("ratingValue")));

  private final RatingService ratings;
  private final ReviewEligibilityService eligibility;

  @GetMapping("/products/{productId}")
  public PagedModel<RatingResponse> findByProductId(
      @PathVariable UUID productId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort) {
    Sort base = SORTS.get(sort.replace(" ", ""));
    if (base == null) {
      throw ValidationException.forField("sort", "must be one of " + SORTS.keySet());
    }
    // Stable tiebreakers so paging never repeats or skips rows with equal sort keys.
    Sort stable = base.and(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
    Pageable pageable =
        PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), stable);
    return new PagedModel<>(ratings.findByProductId(productId, pageable));
  }

  @GetMapping("/products/{productId}/summary")
  public RatingSummaryResponse getSummary(@PathVariable UUID productId) {
    return ratings.getSummary(productId);
  }

  /** The caller's review (if any) and whether they may write one. */
  @GetMapping("/products/{productId}/me")
  public MyRatingStatusResponse mine(
      @PathVariable UUID productId, @AuthenticationPrincipal Jwt jwt) {
    Optional<RatingResponse> own = ratings.findMine(productId, userId(jwt));
    if (own.isPresent()) {
      // A review can only have been written by a buyer, so no remote call is needed.
      return new MyRatingStatusResponse(false, true, own.get());
    }
    boolean purchased = eligibility.hasPurchased(productId);
    return new MyRatingStatusResponse(purchased, purchased, null);
  }

  @GetMapping("/{id}")
  public RatingResponse findById(@PathVariable UUID id) {
    return ratings.findById(id);
  }

  @PostMapping("/products/{productId}")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("isAuthenticated()")
  public RatingResponse create(
      @PathVariable UUID productId,
      @Valid @RequestBody CreateRatingRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    UUID userId = userId(jwt);
    String displayName = eligibility.requireEligibleAuthor(productId, userId);
    return ratings.create(productId, request, userId, displayName);
  }

  @PutMapping("/{id}")
  @PreAuthorize("isAuthenticated()")
  public RatingResponse update(
      @PathVariable UUID id,
      @Valid @RequestBody UpdateRatingRequest request,
      @AuthenticationPrincipal Jwt jwt,
      Authentication authentication) {
    return ratings.update(id, request, userId(jwt), isAdmin(authentication));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void delete(
      @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
    ratings.delete(id, userId(jwt), isAdmin(authentication));
  }

  @PostMapping("/{ratingId}/votes/{voteType}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void addVote(
      @PathVariable UUID ratingId,
      @PathVariable String voteType,
      @AuthenticationPrincipal Jwt jwt) {
    ratings.addVote(ratingId, voteType, userId(jwt));
  }

  private static UUID userId(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  private static boolean isAdmin(Authentication authentication) {
    return authentication.getAuthorities().stream()
        .anyMatch(a -> Roles.ROLE_ADMIN.equals(a.getAuthority()));
  }
}
