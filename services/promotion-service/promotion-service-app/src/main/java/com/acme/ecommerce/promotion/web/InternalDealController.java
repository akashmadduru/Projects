package com.acme.ecommerce.promotion.web;

import com.acme.ecommerce.promotion.service.OfferService;
import com.acme.ecommerce.promotion.service.OfferService.ClaimItem;
import com.acme.ecommerce.promotion.service.OfferService.DealPrice;
import com.acme.ecommerce.promotion.service.OfferService.PriceKey;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Deal prices and claims for order-service (X-Internal-Token). */
@RestController
@RequestMapping("/internal/promotions/deals")
public class InternalDealController {

  public record PricesRequest(List<PriceKey> items, UUID userId) {}

  public record PricesResponse(List<DealPrice> prices) {}

  public record ClaimsRequest(UUID orderId, List<ClaimItem> items) {}

  private final OfferService service;

  public InternalDealController(OfferService service) {
    this.service = service;
  }

  @PostMapping("/prices")
  public PricesResponse prices(@RequestBody PricesRequest request) {
    return new PricesResponse(service.prices(request.items(), request.userId()));
  }

  @PostMapping("/claims")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void claim(@RequestBody ClaimsRequest request) {
    service.claim(request.orderId(), request.items());
  }

  @DeleteMapping("/claims/{orderId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void release(@PathVariable UUID orderId) {
    service.release(orderId);
  }
}
