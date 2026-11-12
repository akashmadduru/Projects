package com.acme.ecommerce.seller.ads;

import com.acme.ecommerce.seller.ads.AdService.ActiveAd;
import com.acme.ecommerce.seller.ads.AdService.AdRequest;
import com.acme.ecommerce.seller.ads.AdService.Campaign;
import com.acme.ecommerce.seller.seller.SellerService;
import com.acme.ecommerce.seller.seller.SellerService.Seller;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdController {

  public record ImpressionsRequest(List<UUID> adIds) {}

  private final AdService ads;
  private final SellerService sellers;

  public AdController(AdService ads, SellerService sellers) {
    this.ads = ads;
    this.sellers = sellers;
  }

  private Seller seller(Jwt jwt) {
    return sellers.requireByUser(UUID.fromString(jwt.getSubject()));
  }

  @GetMapping("/api/seller/ads")
  @PreAuthorize("hasRole('SELLER')")
  public List<Campaign> list(@AuthenticationPrincipal Jwt jwt) {
    return ads.list(seller(jwt).id());
  }

  @PostMapping("/api/seller/ads")
  @PreAuthorize("hasRole('SELLER')")
  @ResponseStatus(HttpStatus.CREATED)
  public Campaign create(@AuthenticationPrincipal Jwt jwt, @RequestBody AdRequest request) {
    Seller s = seller(jwt);
    return ads.create(s.id(), s.status(), request);
  }

  @PutMapping("/api/seller/ads/{id}")
  @PreAuthorize("hasRole('SELLER')")
  public Campaign update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody AdRequest request) {
    return ads.update(seller(jwt).id(), id, request);
  }

  @GetMapping("/internal/ads/active")
  public List<ActiveAd> active(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(defaultValue = "2") int limit) {
    return ads.active(q, categoryId, limit);
  }

  @PostMapping("/api/ads/{adId}/click")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void click(@PathVariable UUID adId) {
    ads.click(adId);
  }

  @PostMapping("/api/ads/impressions")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void impressions(@RequestBody ImpressionsRequest request) {
    ads.impressions(request.adIds());
  }
}
