package com.acme.ecommerce.promotion.web;

import com.acme.ecommerce.promotion.service.ExchangeService;
import com.acme.ecommerce.promotion.service.ExchangeService.Program;
import com.acme.ecommerce.promotion.service.ExchangeService.Quote;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Exchange offers: public programs, JWT quotes. */
@RestController
@RequestMapping("/api/promotions/exchange")
public class ExchangeController {

  public record QuoteRequest(
      @NotNull UUID programId,
      @NotBlank @Size(max = 80) String brand,
      @NotBlank @Size(max = 120) String model,
      @NotBlank String condition,
      @Min(0) int ageYears) {}

  private final ExchangeService service;

  public ExchangeController(ExchangeService service) {
    this.service = service;
  }

  @GetMapping("/programs")
  public List<Program> programs(@RequestParam(required = false) UUID productId) {
    return service.programs(productId);
  }

  @PostMapping("/quote")
  @ResponseStatus(HttpStatus.CREATED)
  public Quote quote(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody QuoteRequest r) {
    return service.createQuote(
        UUID.fromString(jwt.getSubject()), r.programId(), r.brand(), r.model(), r.condition(), r.ageYears());
  }

  @GetMapping("/quotes")
  public List<Quote> mine(@AuthenticationPrincipal Jwt jwt) {
    return service.mine(UUID.fromString(jwt.getSubject()));
  }
}
