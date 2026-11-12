package com.acme.ecommerce.promotion.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/** user-service internal reads (X-Internal-Token): membership + referral lookup. */
@FeignClient(
    name = "user-service",
    contextId = "promotionUserClient",
    url = "${services.user.url:}",
    configuration = InternalFeignConfig.class)
public interface UserFeignClient {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Membership(boolean active) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Referral(UUID referrerUserId, boolean rewarded) {}

  @GetMapping("/internal/users/{userId}/membership")
  Membership membership(@PathVariable("userId") UUID userId);

  @PostMapping("/internal/referrals/by-referee/{userId}/rewarded")
  void referralRewarded(@PathVariable("userId") UUID userId);

  @GetMapping("/internal/referrals/by-referee/{userId}")
  Referral referralByReferee(@PathVariable("userId") UUID userId);
}
