package com.acme.ecommerce.product.client;

import com.acme.ecommerce.inventory.api.InventoryClient;
import org.springframework.cloud.openfeign.FeignClient;

/**
 * product-service's declarative client for inventory-service.
 *
 * <p>THE FIRST REAL CONSUMER OF {@link InventoryClient}, and the ONLY synchronous outbound call
 * this service makes. Exists solely to back {@code CatalogAggregateService}'s read-only aggregate
 * lookup (docs/architecture/design/HLD-catalog-enrichment-and-saga.md S7.3) - the
 * narrowly-approved, explicitly scoped exception to the platform's "no synchronous call between
 * product-service and inventory-service" non-goal (docs/architecture/adr/0003, Decision 2). Do not
 * add a second caller of this interface, and do not add a second {@code @FeignClient} to this
 * service, without an equally explicit approval-gate decision - the non-goal this narrows still
 * applies to everything else.
 *
 * <p>Wired exactly as {@link InventoryClient}'s own javadoc documents: {@code name} matches
 * inventory-service's {@code spring.application.name} (resolved through Eureka via {@code
 * spring-cloud-starter-loadbalancer}, already on this service's classpath), {@code contextId}
 * disambiguates the generated Feign context from any future client this service adds.
 *
 * <p>Timeouts live in {@code feign.client.config.inventory-service.*} (product-service's {@code
 * application.yml}) rather than here - short enough that a slow inventory-service degrades {@code
 * CatalogAggregateService}'s response quickly, not eventually. Failure handling (timeout, 5xx, a
 * genuine 404, or the {@code aggregateCb} breaker being open) is entirely {@code
 * CatalogAggregateService}'s concern, not this interface's - it is a plain declarative HTTP client,
 * same as every other {@code @FeignClient} in the platform.
 */
@FeignClient(name = "inventory-service", contextId = "inventoryClient")
public interface InventoryFeignClient extends InventoryClient {}
