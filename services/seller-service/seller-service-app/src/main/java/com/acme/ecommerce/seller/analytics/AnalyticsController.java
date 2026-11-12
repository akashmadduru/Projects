package com.acme.ecommerce.seller.analytics;

import com.acme.ecommerce.seller.analytics.AnalyticsService.Analytics;
import com.acme.ecommerce.seller.seller.SellerService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalyticsController {

  private final AnalyticsService analytics;
  private final SellerService sellers;

  public AnalyticsController(AnalyticsService analytics, SellerService sellers) {
    this.analytics = analytics;
    this.sellers = sellers;
  }

  @GetMapping("/api/seller/analytics")
  @PreAuthorize("hasRole('SELLER')")
  public Analytics get(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return analytics.compute(sellers.requireByUser(UUID.fromString(jwt.getSubject())).id(), from, to);
  }
}
