package com.acme.ecommerce.seller.client;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** auth-service internal role grant. */
@FeignClient(
    name = "auth-service",
    contextId = "sellerAuthClient",
    url = "${services.auth.url:}",
    configuration = InternalTokenFeignConfig.class)
public interface AuthFeignClient {

  record GrantRole(String role) {}

  @PostMapping("/internal/users/{userId}/roles")
  void grantRole(@PathVariable("userId") UUID userId, @RequestBody GrantRole body);
}
