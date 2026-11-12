package com.acme.ecommerce.promotion.web;

import com.acme.ecommerce.promotion.service.CoinService;
import com.acme.ecommerce.promotion.service.CoinService.CoinsView;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Customer coin balance + history (JWT). */
@RestController
public class CoinController {

  private final CoinService coins;

  public CoinController(CoinService coins) {
    this.coins = coins;
  }

  @GetMapping("/api/coins")
  public CoinsView coins(
      @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String currency) {
    return coins.view(UUID.fromString(jwt.getSubject()), currency);
  }
}
