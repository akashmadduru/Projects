package com.acme.ecommerce.order.web;

import com.acme.ecommerce.order.service.ExchangeService;
import com.acme.ecommerce.order.service.ExchangeService.VerifyResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Phase 10: device inspection outcome for an exchange applied at checkout. */
@RestController
@RequestMapping("/api/admin/exchange")
@PreAuthorize("hasRole('ADMIN')")
public class AdminExchangeController {

  public record VerifyRequest(@NotNull Boolean accepted, BigDecimal finalValue) {}

  private final ExchangeService exchange;

  public AdminExchangeController(ExchangeService exchange) {
    this.exchange = exchange;
  }

  @org.springframework.web.bind.annotation.GetMapping
  public java.util.List<java.util.Map<String, Object>> list(
      @org.springframework.web.bind.annotation.RequestParam(required = false) String status) {
    return exchange.list(status);
  }

  @PostMapping("/{quoteId}/verify")
  public VerifyResult verify(@PathVariable UUID quoteId, @Valid @RequestBody VerifyRequest req) {
    return exchange.verify(quoteId, req.accepted(), req.finalValue());
  }
}
