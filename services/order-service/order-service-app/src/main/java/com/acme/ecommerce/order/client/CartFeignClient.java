package com.acme.ecommerce.order.client;

import com.acme.ecommerce.cart.api.CartClient;
import org.springframework.cloud.openfeign.FeignClient;

/** Calls cart-service via Eureka. Set services.cart.url only to bypass discovery. */
@FeignClient(name = "cart-service", contextId = "cartClient", url = "${services.cart.url:}")
public interface CartFeignClient extends CartClient {}
