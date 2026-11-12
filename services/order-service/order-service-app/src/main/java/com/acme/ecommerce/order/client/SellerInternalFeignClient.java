package com.acme.ecommerce.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** seller-service internal endpoint (X-Internal-Token): the seller account of a user. */
@FeignClient(
    name = "seller-service",
    contextId = "sellerInternalClient",
    url = "${services.seller.url:}",
    configuration = InternalFeignConfig.class)
public interface SellerInternalFeignClient {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SellerRef(UUID sellerId, String status) {}

  @GetMapping("/internal/sellers/by-user/{userId}")
  SellerRef byUser(@PathVariable("userId") UUID userId);
}
