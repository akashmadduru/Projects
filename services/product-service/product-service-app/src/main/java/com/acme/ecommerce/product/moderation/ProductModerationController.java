package com.acme.ecommerce.product.moderation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin review queue for seller-listed products (TD-P9-03). */
@RestController
@RequestMapping("/api/products/admin/moderation")
@PreAuthorize("hasAnyRole('CATALOG_MANAGER','ADMIN')")
public class ProductModerationController {

  public record Decision(@NotBlank String decision, @Size(max = 500) String note) {}

  private final ProductModerationService service;

  public ProductModerationController(ProductModerationService service) {
    this.service = service;
  }

  @GetMapping
  public ProductModerationService.QueuePage queue(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.queue(status, page, size);
  }

  @PostMapping("/{productId}")
  public ProductModerationService.QueueItem decide(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID productId,
      @Valid @RequestBody Decision decision) {
    return service.decide(
        productId, decision.decision(), decision.note(), UUID.fromString(jwt.getSubject()));
  }
}
