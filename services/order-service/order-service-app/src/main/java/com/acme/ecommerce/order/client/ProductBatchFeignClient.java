package com.acme.ecommerce.order.client;

import com.acme.ecommerce.order.client.ProductViewFeignClient.ProductView;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Batched product-service read ({@code GET /api/products/batch?ids=..&detail=true}): the full
 * {@code detail} view of up to 50 products in one request. A separate contextId from {@link
 * ProductViewFeignClient} so that its longer read timeout (see application.yml) does not apply to
 * single lookups. The caller's bearer token is forwarded by the common-web interceptor.
 */
@FeignClient(
    name = "product-service",
    contextId = "productBatchClient",
    url = "${services.product.url:}")
public interface ProductBatchFeignClient {

  /** At most 50 comma-separated ids; unknown ids are simply absent from the result. */
  @GetMapping("/api/products/batch")
  List<ProductView> batch(@RequestParam("ids") String ids, @RequestParam("detail") boolean detail);
}
