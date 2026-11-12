package com.acme.ecommerce.promotion.web;

import com.acme.ecommerce.promotion.api.dto.RedeemRequest;
import com.acme.ecommerce.promotion.api.dto.RedeemResponse;
import com.acme.ecommerce.promotion.service.CouponService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service redemption endpoints; guarded by InternalTokenFilter. */
@RestController
@RequestMapping("/internal/promotions/redemptions")
public class InternalPromotionController {

  private final CouponService service;

  public InternalPromotionController(CouponService service) {
    this.service = service;
  }

  @PostMapping
  public RedeemResponse redeem(@Valid @RequestBody RedeemRequest request) {
    return service.redeem(request);
  }

  @DeleteMapping("/{orderId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void release(@PathVariable UUID orderId) {
    service.release(orderId);
  }
}
