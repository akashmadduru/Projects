package com.acme.ecommerce.promotion.web;

import com.acme.ecommerce.promotion.repository.DealRepository.Deal;
import com.acme.ecommerce.promotion.service.OfferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin CRUD for deals of the day. */
@RestController
@RequestMapping("/api/admin/deals")
@PreAuthorize("hasRole('ADMIN')")
public class AdminDealController {

  public record DealRequest(
      @NotNull UUID productId,
      UUID variantId,
      String title,
      @NotNull @DecimalMin("0.01") BigDecimal dealPrice,
      @NotBlank String currency,
      Instant startsAt,
      @NotNull Instant endsAt,
      @Min(1) int quantityLimit,
      Boolean active,
      Instant membersOnlyUntil) {}

  private final OfferService service;

  public AdminDealController(OfferService service) {
    this.service = service;
  }

  @GetMapping
  public List<Deal> list() {
    return service.adminList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Deal create(@Valid @RequestBody DealRequest r) {
    return service.create(toDeal(r));
  }

  @PutMapping("/{id}")
  public Deal update(@PathVariable UUID id, @Valid @RequestBody DealRequest r) {
    return service.update(id, toDeal(r));
  }

  @PostMapping("/{id}/deactivate")
  public Deal deactivate(@PathVariable UUID id) {
    return service.deactivate(id);
  }

  private static Deal toDeal(DealRequest r) {
    return new Deal(
        null,
        r.productId(),
        r.variantId(),
        r.title(),
        r.dealPrice(),
        r.currency(),
        r.startsAt(),
        r.endsAt(),
        r.quantityLimit(),
        0,
        r.active() == null || r.active(),
        r.membersOnlyUntil());
  }
}
