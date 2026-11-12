package com.acme.ecommerce.rating.client;

import com.acme.ecommerce.order.api.OrderPurchaseClient;
import org.springframework.cloud.openfeign.FeignClient;

/** Calls order-service via Eureka. Set services.order.url only to bypass discovery. */
@FeignClient(
    name = "order-service",
    contextId = "orderPurchaseClient",
    url = "${services.order.url:}")
public interface OrderPurchaseFeignClient extends OrderPurchaseClient {}
