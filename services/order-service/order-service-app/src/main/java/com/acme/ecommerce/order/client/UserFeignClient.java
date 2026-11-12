package com.acme.ecommerce.order.client;

import com.acme.ecommerce.user.api.UserAddressClient;
import org.springframework.cloud.openfeign.FeignClient;

/** Calls user-service via Eureka. Set services.user.url only to bypass discovery. */
@FeignClient(name = "user-service", contextId = "userAddressClient", url = "${services.user.url:}")
public interface UserFeignClient extends UserAddressClient {}
