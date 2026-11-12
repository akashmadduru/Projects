package com.acme.ecommerce.product.client;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * product-service's declarative client for rating-service's public rating summary.
 *
 * <p>Used ONLY by {@code RatingEventListener} to refresh the denormalized rating fields: the
 * summary is authoritative, so event ordering and replays do not matter. The endpoint is public (no
 * token needed), which is what makes this safe to call from a Kafka consumer thread that has no
 * inbound request to relay a token from. Timeouts live in {@code feign.client.config.rating-service
 * .*} in application.yml; Spring Cloud's default Retryer is {@code NEVER_RETRY}.
 */
@FeignClient(name = "rating-service", contextId = "ratingClient")
public interface RatingFeignClient {

  @GetMapping(
      value = "/api/ratings/products/{productId}/summary",
      produces = MediaType.APPLICATION_JSON_VALUE)
  RatingSummary summary(@PathVariable("productId") UUID productId);
}
