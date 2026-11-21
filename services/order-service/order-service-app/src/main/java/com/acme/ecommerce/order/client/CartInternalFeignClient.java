package com.acme.ecommerce.order.client;

import com.acme.ecommerce.cart.api.dto.ConsumedLinesRequest;
import com.acme.ecommerce.cart.api.dto.ConsumedLinesResponse;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * cart-service internal endpoint (X-Internal-Token, no user token): removes the lines an order
 * consumed from that user's cart. Used by the outbox relay and the inline first attempt.
 */
@FeignClient(
    name = "cart-service",
    contextId = "cartInternalClient",
    url = "${services.cart.url:}",
    configuration = {InternalFeignConfig.class, NoUserTokenFeignConfig.class})
public interface CartInternalFeignClient {

  @PostMapping("/internal/carts/{userId}/consumed")
  ConsumedLinesResponse consumed(
      @PathVariable("userId") UUID userId, @RequestBody ConsumedLinesRequest request);
}
