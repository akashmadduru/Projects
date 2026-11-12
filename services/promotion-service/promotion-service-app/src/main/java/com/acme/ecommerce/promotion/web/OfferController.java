package com.acme.ecommerce.promotion.web;

import com.acme.ecommerce.promotion.service.OfferService;
import com.acme.ecommerce.promotion.service.OfferService.DealView;
import com.acme.ecommerce.promotion.service.OfferService.Offers;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public offers and active deals. */
@RestController
@RequestMapping("/api/promotions")
public class OfferController {

  private final OfferService service;

  public OfferController(OfferService service) {
    this.service = service;
  }

  @GetMapping("/offers")
  public Offers offers(
      @RequestParam(required = false) UUID productId,
      @RequestParam(required = false) BigDecimal price,
      @RequestParam(required = false) String currency) {
    return service.offers(price, currency);
  }

  @GetMapping("/deals/active")
  public List<DealView> active(
      @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID productId) {
    return service.activeDeals(productId, jwt == null ? null : UUID.fromString(jwt.getSubject()));
  }
}
