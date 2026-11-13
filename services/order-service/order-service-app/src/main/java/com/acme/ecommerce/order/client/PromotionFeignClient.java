package com.acme.ecommerce.order.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * promotion-service. validate() is the user-facing endpoint (the caller's bearer token is relayed by
 * common-web); redeem/release are internal (X-Internal-Token).
 */
@FeignClient(
    name = "promotion-service",
    contextId = "promotionClient",
    url = "${services.promotion.url:}",
    configuration = InternalFeignConfig.class)
public interface PromotionFeignClient {

  record ValidateRequest(
      String code, BigDecimal subtotal, String currency, Boolean firstOrder) {}

  record Validation(String code, String description, BigDecimal discountAmount, String currency) {}

  record RedeemRequest(
      String code,
      UUID userId,
      UUID orderId,
      BigDecimal subtotal,
      String currency,
      boolean firstOrder) {}

  record Redemption(BigDecimal discountAmount) {}

  @PostMapping("/api/promotions/coupons/validate")
  Validation validate(@RequestBody ValidateRequest request);

  @PostMapping("/internal/promotions/redemptions")
  Redemption redeem(@RequestBody RedeemRequest request);

  @DeleteMapping("/internal/promotions/redemptions/{orderId}")
  void release(@PathVariable("orderId") UUID orderId);

  record DealKey(UUID productId, UUID variantId) {}

  record DealPricesRequest(List<DealKey> items, UUID userId) {}

  record CoinRedeem(UUID userId, int coins, UUID orderId) {}

  @PostMapping("/internal/coins/redeem")
  void redeemCoins(@RequestBody CoinRedeem request);

  @DeleteMapping("/internal/coins/redeem/{orderId}")
  void releaseCoins(@PathVariable("orderId") UUID orderId);

  @org.springframework.web.bind.annotation.GetMapping("/internal/coins/{userId}")
  java.util.Map<String, Integer> coinBalance(@PathVariable("userId") UUID userId);

  record DealPrice(UUID productId, UUID variantId, UUID dealId, BigDecimal dealPrice, int remaining) {}

  record DealPricesResponse(List<DealPrice> prices) {}

  record DealClaimItem(UUID dealId, int quantity) {}

  record DealClaimsRequest(UUID orderId, List<DealClaimItem> items) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  record ExchangeQuoteView(
      UUID id, UUID userId, UUID programId, BigDecimal quotedValue, String currency, String status) {}

  record ExchangeApplyRequest(UUID quoteId, UUID userId, UUID orderId) {}

  record ExchangeVerifyRequest(boolean accepted, BigDecimal finalValue) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  record ExchangeVerifyResponse(
      UUID quoteId,
      UUID orderId,
      String status,
      BigDecimal quotedValue,
      BigDecimal finalValue,
      BigDecimal shortfall) {}

  @org.springframework.web.bind.annotation.GetMapping("/internal/promotions/exchange/quotes/{id}")
  ExchangeQuoteView exchangeQuote(
      @PathVariable("id") UUID id,
      @org.springframework.web.bind.annotation.RequestParam("userId") UUID userId);

  @org.springframework.web.bind.annotation.GetMapping("/internal/promotions/exchange/quotes")
  java.util.List<java.util.Map<String, Object>> exchangeQuotes(
      @org.springframework.web.bind.annotation.RequestParam(value = "status", required = false) String status);

  @PostMapping("/internal/promotions/exchange/apply")
  void applyExchange(@RequestBody ExchangeApplyRequest request);

  @DeleteMapping("/internal/promotions/exchange/apply/{orderId}")
  void releaseExchange(@PathVariable("orderId") UUID orderId);

  @PostMapping("/internal/promotions/exchange/{quoteId}/verify")
  ExchangeVerifyResponse verifyExchange(
      @PathVariable("quoteId") UUID quoteId, @RequestBody ExchangeVerifyRequest request);

  @PostMapping("/internal/promotions/deals/prices")
  DealPricesResponse dealPrices(@RequestBody DealPricesRequest request);

  @PostMapping("/internal/promotions/deals/claims")
  void claimDeals(@RequestBody DealClaimsRequest request);

  @DeleteMapping("/internal/promotions/deals/claims/{orderId}")
  void releaseDeals(@PathVariable("orderId") UUID orderId);
}
