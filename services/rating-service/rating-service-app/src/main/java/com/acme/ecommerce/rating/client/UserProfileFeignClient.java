package com.acme.ecommerce.rating.client;

import com.acme.ecommerce.user.api.dto.UserProfileResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Calls user-service via Eureka for the caller's own profile (token relayed). Set services.user.url
 * only to bypass discovery.
 */
@FeignClient(name = "user-service", contextId = "userProfileClient", url = "${services.user.url:}")
public interface UserProfileFeignClient {

  @GetMapping(value = "/api/users/me", produces = MediaType.APPLICATION_JSON_VALUE)
  UserProfileResponse me();
}
