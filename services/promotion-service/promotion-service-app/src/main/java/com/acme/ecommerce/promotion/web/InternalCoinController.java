package com.acme.ecommerce.promotion.web;

import com.acme.ecommerce.promotion.service.CoinService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Coin redemption for order-service (X-Internal-Token). */
@RestController
public class InternalCoinController {

  public record RedeemRequest(@NotNull UUID userId, @Min(1) int coins, @NotNull UUID orderId) {}

  private final CoinService coins;

  public InternalCoinController(CoinService coins) {
    this.coins = coins;
  }

  @GetMapping("/internal/coins/{userId}")
  public Map<String, Integer> balance(@PathVariable UUID userId) {
    return Map.of("balance", coins.balance(userId));
  }

  @PostMapping("/internal/coins/redeem")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void redeem(@Valid @RequestBody RedeemRequest r) {
    coins.redeem(r.userId(), r.coins(), r.orderId());
  }

  @DeleteMapping("/internal/coins/redeem/{orderId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void release(@PathVariable UUID orderId) {
    coins.releaseRedeem(orderId);
  }
}
