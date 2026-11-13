package com.acme.ecommerce.rating.web;

import com.acme.ecommerce.rating.api.dto.RatingResponse;
import com.acme.ecommerce.rating.service.RatingService;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Review moderation list for the admin console. Deletion reuses {@code DELETE /api/ratings/{id}},
 * which already allows ADMIN and publishes the event that recomputes the product's averages.
 */
@RestController
@RequestMapping("/api/admin/ratings")
@PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
public class AdminRatingController {

  private static final int MAX_PAGE_SIZE = 50;

  private final RatingService ratings;

  public AdminRatingController(RatingService ratings) {
    this.ratings = ratings;
  }

  @GetMapping
  public PagedModel<RatingResponse> search(
      @RequestParam(required = false) UUID productId,
      @RequestParam(required = false) Integer rating,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean flagged,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    Integer stars = rating != null && rating >= 1 && rating <= 5 ? rating : null;
    PageRequest pageable =
        PageRequest.of(
            Math.max(page, 0),
            Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
            Sort.by(Sort.Direction.DESC, "createdAt"));
    return new PagedModel<>(ratings.adminSearch(productId, stars, q, flagged, pageable));
  }

  public record ModerateRequest(@jakarta.validation.constraints.NotNull Boolean approve) {}

  /** approve=true keeps the review (counted again); approve=false deletes it. */
  @org.springframework.web.bind.annotation.PostMapping("/{id}/moderate")
  public RatingResponse moderate(
      @org.springframework.web.bind.annotation.PathVariable UUID id,
      @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody ModerateRequest r) {
    return ratings.moderate(id, r.approve());
  }
}
