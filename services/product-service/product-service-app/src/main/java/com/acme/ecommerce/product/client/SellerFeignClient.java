package com.acme.ecommerce.product.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/** seller-service internal endpoints (X-Internal-Token). */
@FeignClient(name = "seller-service", contextId = "sellerClient")
public interface SellerFeignClient {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SellerRef(UUID sellerId, String status) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SellerInfo(
      UUID sellerId, String slug, String displayName, BigDecimal ratingAvg, Integer ratingCount, String status) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record AdRef(UUID adId, UUID productId, BigDecimal bid) {}

  @GetMapping("/internal/sellers/by-user/{userId}")
  SellerRef byUser(
      @PathVariable("userId") String userId, @RequestHeader("X-Internal-Token") String token);

  @GetMapping("/internal/sellers")
  List<SellerInfo> batch(
      @RequestParam("ids") String ids, @RequestHeader("X-Internal-Token") String token);

  @GetMapping("/internal/ads/active")
  List<AdRef> activeAds(
      @RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "categoryId", required = false) UUID categoryId,
      @RequestParam(name = "limit", defaultValue = "2") int limit,
      @RequestHeader("X-Internal-Token") String token);
}
