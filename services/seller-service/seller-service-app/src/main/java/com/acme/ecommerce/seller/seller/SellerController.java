package com.acme.ecommerce.seller.seller;

import com.acme.ecommerce.seller.Paged;
import com.acme.ecommerce.seller.seller.SellerService.ApplyRequest;
import com.acme.ecommerce.seller.seller.SellerService.ByUser;
import com.acme.ecommerce.seller.seller.SellerService.InternalSeller;
import com.acme.ecommerce.seller.seller.SellerService.PublicProfile;
import com.acme.ecommerce.seller.seller.SellerService.Seller;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Onboarding (JWT), admin review, public profile and internal lookups. */
@RestController
public class SellerController {

  public record DecisionRequest(boolean approve, String reason) {}

  public record SuspendRequest(String reason) {}

  private final SellerService sellers;

  public SellerController(SellerService sellers) {
    this.sellers = sellers;
  }

  @PostMapping("/api/sellers/apply")
  public Seller apply(@AuthenticationPrincipal Jwt jwt, @RequestBody ApplyRequest request) {
    return sellers.apply(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("email"), request);
  }

  @GetMapping("/api/sellers/me")
  public Seller me(@AuthenticationPrincipal Jwt jwt) {
    return sellers.findByUser(UUID.fromString(jwt.getSubject()))
        .orElseThrow(() -> new com.acme.ecommerce.common.core.error.ResourceNotFoundException("Seller", "me"));
  }

  @GetMapping("/api/sellers/{slug}")
  public PublicProfile profile(@PathVariable String slug) {
    return sellers.profile(slug);
  }

  @GetMapping("/api/admin/sellers")
  @PreAuthorize("hasAnyRole('ADMIN','SUPPORT','FINANCE')")
  public Paged<Seller> adminList(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return sellers.adminList(status, page, size);
  }

  @GetMapping("/api/admin/sellers/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','SUPPORT','FINANCE')")
  public Seller adminGet(@PathVariable UUID id) {
    return sellers.adminGet(id);
  }

  @PostMapping("/api/admin/sellers/{id}/decision")
  @PreAuthorize("hasRole('ADMIN')")
  public Seller decide(@PathVariable UUID id, @RequestBody DecisionRequest request) {
    return sellers.decide(id, request.approve(), request.reason());
  }

  @PostMapping("/api/admin/sellers/{id}/suspend")
  @PreAuthorize("hasRole('ADMIN')")
  public Seller suspend(@PathVariable UUID id, @RequestBody(required = false) SuspendRequest request) {
    return sellers.suspend(id, request == null ? null : request.reason());
  }

  // ---- internal (X-Internal-Token) ----

  @GetMapping("/internal/sellers/by-user/{userId}")
  public ByUser byUser(@PathVariable UUID userId) {
    return sellers.byUser(userId);
  }

  @GetMapping("/internal/sellers/{id}")
  public InternalSeller internalGet(@PathVariable UUID id) {
    return sellers.internalGet(id);
  }

  @GetMapping("/internal/sellers")
  public List<InternalSeller> internalBatch(@RequestParam String ids) {
    return sellers.internalBatch(Arrays.stream(ids.split(",")).filter(s -> !s.isBlank()).map(String::trim).map(UUID::fromString).toList());
  }
}
