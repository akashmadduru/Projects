package com.acme.ecommerce.rating.web;

import com.acme.ecommerce.common.core.error.CodedException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.rating.api.dto.RatingMediaResponse;
import com.acme.ecommerce.rating.service.QaService;
import com.acme.ecommerce.rating.service.ReviewMediaService;
import com.acme.ecommerce.rating.web.dto.QaDtos.AnswerRequest;
import com.acme.ecommerce.rating.web.dto.QaDtos.AnswerResponse;
import com.acme.ecommerce.rating.web.dto.QaDtos.QuestionRequest;
import com.acme.ecommerce.rating.web.dto.QaDtos.QuestionResponse;
import com.acme.ecommerce.rating.web.dto.QaDtos.UpvoteResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
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
  private final QaService qa;
  private final ReviewMediaService mediaService;

  // ---- Q&A ---------------------------------------------------------------------------------

  @GetMapping("/products/{productId}/questions")
  public PagedModel<QuestionResponse> questions(
      @PathVariable UUID productId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(defaultValue = "top") String sort) {
    Sort s =
        "newest".equals(sort)
            ? Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))
            : Sort.by(
                Sort.Order.desc("upvotes"), Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
    return new PagedModel<>(
        qa.listQuestions(
            productId,
            PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), s)));
  }

  @GetMapping("/questions/{questionId}/answers")
  public PagedModel<AnswerResponse> answers(
      @PathVariable UUID questionId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size) {
    Sort s = Sort.by(Sort.Order.desc("upvotes"), Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
    return new PagedModel<>(
        qa.listAnswers(
            questionId,
            PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), s)));
  }

  @PostMapping("/products/{productId}/questions")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("isAuthenticated()")
  public QuestionResponse ask(
      @PathVariable UUID productId,
      @Valid @RequestBody QuestionRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    return qa.ask(productId, request.text(), userId(jwt), eligibility.displayName());
  }

  @PostMapping("/questions/{questionId}/answers")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("isAuthenticated()")
  public AnswerResponse answer(
      @PathVariable UUID questionId,
      @Valid @RequestBody AnswerRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    UUID productId = qa.productIdOfQuestion(questionId);
    boolean buyer;
    try {
      buyer = eligibility.hasPurchased(productId);
    } catch (CodedException e) {
      buyer = false; // best-effort badge; an order-service blip must not block answering
    }
    return qa.answer(questionId, request.text(), userId(jwt), eligibility.displayName(), buyer);
  }

  @PostMapping("/questions/{questionId}/upvote")
  @PreAuthorize("isAuthenticated()")
  public UpvoteResponse upvoteQuestion(
      @PathVariable UUID questionId, @AuthenticationPrincipal Jwt jwt) {
    return qa.upvoteQuestion(questionId, userId(jwt));
  }

  @PostMapping("/answers/{answerId}/upvote")
  @PreAuthorize("isAuthenticated()")
  public UpvoteResponse upvoteAnswer(
      @PathVariable UUID answerId, @AuthenticationPrincipal Jwt jwt) {
    return qa.upvoteAnswer(answerId, userId(jwt));
  }

  // ---- Review media ------------------------------------------------------------------------

  @PostMapping(path = "/{ratingId}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("isAuthenticated()")
  public RatingMediaResponse uploadMedia(
      @PathVariable UUID ratingId,
      @RequestPart("file") MultipartFile file,
      @AuthenticationPrincipal Jwt jwt) {
    return mediaService.upload(ratingId, file, userId(jwt));
  }

  @DeleteMapping("/{ratingId}/media/{mediaId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void deleteMedia(
      @PathVariable UUID ratingId,
      @PathVariable UUID mediaId,
      @AuthenticationPrincipal Jwt jwt,
      Authentication authentication) {
    mediaService.delete(ratingId, mediaId, userId(jwt), isAdmin(authentication));
  }

  @GetMapping("/media/{mediaId}")
  public ResponseEntity<byte[]> media(@PathVariable UUID mediaId) {
    ReviewMediaService.StoredFile f = mediaService.load(mediaId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(f.contentType()))
        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
        .header("X-Content-Type-Options", "nosniff")
        .body(f.bytes());
  }

  @GetMapping("/products/{productId}")
  public PagedModel<RatingResponse> findByProductId(
      @PathVariable UUID productId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort,
      @RequestParam(required = false) Integer stars,
      @RequestParam(defaultValue = "false") boolean verified,
      @RequestParam(defaultValue = "false") boolean withMedia) {
    if (stars != null && (stars < 1 || stars > 5)) {
      throw ValidationException.forField("stars", "must be between 1 and 5");
    }
    Sort base = SORTS.get(sort.replace(" ", ""));
    if (base == null) {
      throw ValidationException.forField("sort", "must be one of " + SORTS.keySet());
    }
    // Stable tiebreakers so paging never repeats or skips rows with equal sort keys.
    Sort stable = base.and(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
    Pageable pageable =
        PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), stable);
    return new PagedModel<>(ratings.findByProductId(productId, stars, verified, withMedia, pageable));
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
    return ratings.create(productId, request, userId, displayName, eligibility.accountCreatedAt());
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
