package com.acme.ecommerce.inventory.api;

import com.acme.ecommerce.inventory.api.dto.InventoryResponse;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Declarative client for the parts of inventory-service another service may legitimately call.
 *
 * <p>NOTHING CONSUMES THIS YET, and that is intentional - published now, the moment the endpoint is
 * written, by the service that owns it, rather than reverse-engineered from a URL by whichever
 * service happens to need it first. Mirrors {@code ProductClient}'s shape exactly.
 *
 * <p>Annotated with Spring MVC annotations rather than feign's own, because Spring Cloud
 * OpenFeign's default contract is the Spring MVC one. Deliberately NOT annotated
 * {@code @FeignClient}: the consumer supplies that, so it owns the service id, the fallback and the
 * configuration class. A consumer wires it up as:
 *
 * <pre>{@code
 * @FeignClient(name = "inventory-service", contextId = "inventoryClient")
 * public interface MyInventoryClient extends InventoryClient {}
 * }</pre>
 *
 * <p>DELIBERATELY ABSENT: the paged list endpoint, for the same reason {@code ProductClient} omits
 * it - Feign cannot bind a Spring {@code Pageable} without the consumer also pulling in Spring
 * Cloud OpenFeign's page support, a dependency this contract module refuses to force on anyone.
 *
 * <p>The caller's {@code Authorization} and {@code X-Correlation-Id} headers are relayed
 * automatically by {@code CorrelationAndAuthForwardingInterceptor} in common-web, so neither
 * appears in these signatures.
 */
public interface InventoryClient {

  /**
   * Base path for every inventory endpoint.
   *
   * <p>The gateway's route table matches {@code /api/inventory/**} and the service is registered as
   * {@code INVENTORY-SERVICE}; both halves have to agree with this constant.
   */
  String BASE_PATH = "/api/inventory";

  /**
   * Fetches one inventory row by id.
   *
   * <p>An unknown id answers 404 with an RFC 9457 problem document carrying {@code
   * code=RESOURCE_NOT_FOUND}. Under Feign's default error decoder that surfaces as a {@code
   * FeignException.NotFound}, which a consumer should translate rather than propagate.
   *
   * @param id the inventory row identifier
   * @return the inventory row
   */
  @GetMapping(value = BASE_PATH + "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  InventoryResponse findById(@PathVariable("id") UUID id);

  /**
   * Fetches the stock-level row for a product, if one has been provisioned.
   *
   * <p>Added for {@code docs/architecture/design/HLD-catalog-enrichment-and-saga.md} S7.3's
   * narrowly-approved read-only aggregate call: product-service's {@code CatalogAggregateService}
   * is the first real consumer of this interface, calling this method through its own {@code
   * aggregateCb} circuit breaker (docs/architecture/adr/0003, Decision 2).
   *
   * <p>An unprovisioned {@code productId} answers 404 with an RFC 9457 problem document carrying
   * {@code code=RESOURCE_NOT_FOUND} - the same "advisory, not authoritative" contract {@link
   * #findById} already documents. Under Feign's default error decoder that surfaces as a {@code
   * FeignException.NotFound}; a consumer composing an aggregate response should translate that (and
   * any other failure) into "no inventory data available" rather than propagate it.
   *
   * @param productId the product to look up stock for
   * @return the matching stock-level row
   */
  @GetMapping(
      value = BASE_PATH + "/by-product/{productId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  InventoryResponse findByProductId(@PathVariable("productId") UUID productId);
}
