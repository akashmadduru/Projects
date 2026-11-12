package com.acme.ecommerce.promotion.web;

import com.acme.ecommerce.promotion.api.dto.AvailableCouponResponse;
import com.acme.ecommerce.promotion.api.dto.CouponValidationResponse;
import com.acme.ecommerce.promotion.api.dto.ValidateCouponRequest;
import com.acme.ecommerce.promotion.service.CouponService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Customer coupon endpoints. */
@RestController
@RequestMapping("/api/promotions/coupons")
public class PromotionController {

  private final CouponService service;

  public PromotionController(CouponService service) {
    this.service = service;
  }

  @PostMapping("/validate")
  public CouponValidationResponse validate(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ValidateCouponRequest request) {
    return service.validate(UUID.fromString(jwt.getSubject()), request);
  }

  @GetMapping("/available")
  public List<AvailableCouponResponse> available(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) BigDecimal subtotal,
      @RequestParam(required = false) String currency) {
    return service.available(UUID.fromString(jwt.getSubject()), subtotal, currency);
  }
}
