package com.acme.ecommerce.rating.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Calls auth-service via Eureka for the caller's own account (token relayed). Used only as the
 * display-name fallback: the registration display name lives here, while user-service's profile
 * display name stays null until the user edits their profile. Set services.auth.url only to bypass
 * discovery.
 */
@FeignClient(name = "auth-service", contextId = "authAccountClient", url = "${services.auth.url:}")
public interface AuthAccountFeignClient {

  @GetMapping(value = "/api/auth/me", produces = MediaType.APPLICATION_JSON_VALUE)
  Account me();

  /** Only the field this service needs; everything else in the response is ignored. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Account(String displayName, java.time.Instant createdAt) {}
}
