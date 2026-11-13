package com.acme.ecommerce.promotion.web;

import com.acme.ecommerce.promotion.service.ExchangeService;
import com.acme.ecommerce.promotion.service.ExchangeService.Quote;
import com.acme.ecommerce.promotion.service.ExchangeService.VerifyResult;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Exchange quote reads/applies/verification for order-service (X-Internal-Token). */
@RestController
@RequestMapping("/internal/promotions/exchange")
public class InternalExchangeController {

  public record ApplyRequest(UUID quoteId, UUID userId, UUID orderId) {}

  public record VerifyRequest(boolean accepted, BigDecimal finalValue) {}

  private final ExchangeService service;

  public InternalExchangeController(ExchangeService service) {
    this.service = service;
  }

  @GetMapping("/quotes/{id}")
  public Quote quote(@PathVariable UUID id, @RequestParam UUID userId) {
    return service.findOwned(id, userId);
  }

  @GetMapping("/quotes")
  public java.util.List<Quote> list(@RequestParam(required = false) String status) {
    return service.listByStatus(status);
  }

  @PostMapping("/apply")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void apply(@RequestBody ApplyRequest r) {
    service.apply(r.quoteId(), r.userId(), r.orderId());
  }

  @DeleteMapping("/apply/{orderId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void release(@PathVariable UUID orderId) {
    service.release(orderId);
  }

  @PostMapping("/{quoteId}/verify")
  public VerifyResult verify(@PathVariable UUID quoteId, @RequestBody VerifyRequest r) {
    return service.verify(quoteId, r.accepted(), r.finalValue());
  }
}
