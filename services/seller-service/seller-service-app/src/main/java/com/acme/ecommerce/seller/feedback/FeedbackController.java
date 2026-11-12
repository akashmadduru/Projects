package com.acme.ecommerce.seller.feedback;

import com.acme.ecommerce.seller.Paged;
import com.acme.ecommerce.seller.feedback.FeedbackService.Feedback;
import com.acme.ecommerce.seller.feedback.FeedbackService.FeedbackRequest;
import com.acme.ecommerce.seller.seller.SellerService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FeedbackController {

  private final FeedbackService feedback;
  private final SellerService sellers;

  public FeedbackController(FeedbackService feedback, SellerService sellers) {
    this.feedback = feedback;
    this.sellers = sellers;
  }

  @PostMapping("/api/sellers/{id}/feedback")
  @ResponseStatus(HttpStatus.CREATED)
  public Feedback create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody FeedbackRequest request) {
    return feedback.create(UUID.fromString(jwt.getSubject()), id, request);
  }

  @GetMapping("/api/sellers/{id}/feedback")
  public Paged<Feedback> list(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return feedback.list(id, page, size);
  }

  /** The calling seller's own feedback. */
  @GetMapping("/api/seller/feedback")
  @PreAuthorize("hasRole('SELLER')")
  public Paged<Feedback> mine(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return feedback.list(sellers.requireByUser(UUID.fromString(jwt.getSubject())).id(), page, size);
  }
}
