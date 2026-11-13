package com.acme.ecommerce.seller.ledger;

import com.acme.ecommerce.seller.Paged;
import com.acme.ecommerce.seller.ledger.LedgerService.LedgerEntry;
import com.acme.ecommerce.seller.ledger.LedgerService.Payout;
import com.acme.ecommerce.seller.ledger.LedgerService.PayoutRun;
import com.acme.ecommerce.seller.seller.SellerService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FinanceController {

  public record RunRequest(Integer minAgeDays) {}

  private final LedgerService ledger;
  private final SellerService sellers;

  public FinanceController(LedgerService ledger, SellerService sellers) {
    this.ledger = ledger;
    this.sellers = sellers;
  }

  @GetMapping("/api/seller/ledger")
  @PreAuthorize("hasRole('SELLER')")
  public Paged<LedgerEntry> ledger(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return ledger.ledger(sellers.requireByUser(UUID.fromString(jwt.getSubject())).id(), page, size);
  }

  @GetMapping("/api/seller/payouts")
  @PreAuthorize("hasRole('SELLER')")
  public Paged<Payout> payouts(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return ledger.payouts(sellers.requireByUser(UUID.fromString(jwt.getSubject())).id(), page, size);
  }

  @GetMapping("/api/admin/payouts")
  @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
  public Paged<Payout> adminPayouts(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return ledger.payouts(null, page, size);
  }

  @PostMapping("/api/admin/payouts/run")
  @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
  public PayoutRun run(@RequestBody(required = false) RunRequest request, @RequestParam(required = false) Integer minAgeDays) {
    Integer days = request != null && request.minAgeDays() != null ? request.minAgeDays() : minAgeDays;
    return ledger.run(days);
  }
}
