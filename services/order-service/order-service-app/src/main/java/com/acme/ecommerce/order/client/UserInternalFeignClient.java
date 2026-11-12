package com.acme.ecommerce.order.client;

import com.acme.ecommerce.user.api.dto.AddressResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** user-service internal endpoints (X-Internal-Token): membership + address by user id. */
@FeignClient(
    name = "user-service",
    contextId = "userInternalClient",
    url = "${services.user.url:}",
    configuration = InternalFeignConfig.class)
public interface UserInternalFeignClient {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Membership(boolean active, String plan, String endsAt) {}

  @GetMapping("/internal/users/{userId}/membership")
  Membership membership(@PathVariable("userId") UUID userId);

  @GetMapping("/internal/users/{userId}/addresses/{addressId}")
  AddressResponse address(
      @PathVariable("userId") UUID userId, @PathVariable("addressId") UUID addressId);
}
