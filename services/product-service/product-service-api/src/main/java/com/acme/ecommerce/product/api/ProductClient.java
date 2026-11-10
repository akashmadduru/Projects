package com.acme.ecommerce.product.api;

import com.acme.ecommerce.product.api.dto.ProductResponse;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Declarative client for the parts of product-service another service may legitimately call.
 *
 * <p>NOTHING CONSUMES THIS YET, and that is intentional. It is published in Phase 1E so that the
 * contract is defined by the service that owns it, at the moment the endpoint is written, rather
 * than reverse-engineered from a URL by whichever service happens to need it first (Phase 2's order
 * service, most likely).
 *
 * <p>Annotated with Spring MVC annotations rather than feign's own, because Spring Cloud
 * OpenFeign's default contract is the Spring MVC one. Deliberately NOT annotated
 * {@code @FeignClient}: the consumer supplies that, so it owns the service id, the fallback and the
 * configuration class - exactly as {@code AuthClient} does. A consumer wires it up as:
 *
 * <pre>{@code
 * @FeignClient(name = "product-service", contextId = "productClient")
 * public interface MyProductClient extends ProductClient {}
 * }</pre>
 *
 * <p>DELIBERATELY ABSENT: the paged list endpoint. Pagination parameters travel as a Spring {@code
 * Pageable}, which Feign cannot bind without the consumer also pulling in Spring Cloud OpenFeign's
 * page support - a dependency this contract module refuses to force on anyone. A service that needs
 * to page the catalog wants a purpose-built query anyway, not a generic page walk.
 *
 * <p>The caller's {@code Authorization} and {@code X-Correlation-Id} headers are relayed
 * automatically by {@code CorrelationAndAuthForwardingInterceptor} in common-web, so neither
 * appears in these signatures.
 */
public interface ProductClient {

  /**
   * Base path for every product endpoint.
   *
   * <p>The gateway's route table matches {@code /api/products/**} and the service is registered as
   * {@code PRODUCT-SERVICE}; both halves have to agree with this constant.
   */
  String BASE_PATH = "/api/products";

  /**
   * Fetches one product by id.
   *
   * <p>An unknown id answers 404 with an RFC 9457 problem document carrying {@code
   * code=RESOURCE_NOT_FOUND}. Under Feign's default error decoder that surfaces as a {@code
   * FeignException.NotFound}, which a consumer should translate rather than propagate.
   *
   * @param id the product identifier
   * @return the product
   */
  @GetMapping(value = BASE_PATH + "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  ProductResponse findById(@PathVariable("id") UUID id);
}
