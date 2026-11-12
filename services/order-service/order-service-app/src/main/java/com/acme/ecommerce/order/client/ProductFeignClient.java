package com.acme.ecommerce.order.client;

import com.acme.ecommerce.product.api.ProductClient;
import org.springframework.cloud.openfeign.FeignClient;

/** Calls product-service via Eureka. Set services.product.url only to bypass discovery. */
@FeignClient(
    name = "product-service",
    contextId = "productClient",
    url = "${services.product.url:}")
public interface ProductFeignClient extends ProductClient {}
