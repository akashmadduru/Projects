package com.acme.ecommerce.product;

import com.acme.ecommerce.product.config.ProductProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * product-service entry point.
 *
 * <p>Blocking Spring MVC on VIRTUAL THREADS ({@code spring.threads.virtual.enabled=true}), not
 * WebFlux - the same call auth-service made, for the same reason. Each request here is a single
 * indexed query behind an ordinary request/response contract; virtual threads give the concurrency
 * a reactive stack would without any of its comprehensibility cost.
 *
 * <p>Registers with Eureka under {@code spring.application.name = product-service}, which Eureka
 * uppercases to {@code PRODUCT-SERVICE}. THAT IS THE SERVICE ID THE GATEWAY'S ROUTE TABLE ALREADY
 * TARGETS ({@code lb://PRODUCT-SERVICE}). Rename the application and the route stops resolving -
 * silently, because a route whose target is unregistered is indistinguishable from one whose target
 * is down.
 *
 * <p>{@code @EnableFeignClients} IS NEW HERE (Phase 3, docs/HLD-catalog-enrichment-and-saga.md
 * S7.3). {@code com.acme.ecommerce.product.client.InventoryFeignClient} is this service's first
 * real outbound call - the one narrowly-approved read-only exception to the platform's "no
 * synchronous call between product-service and inventory-service" non-goal (docs/adr/0003, Decision
 * 2). It stays scoped to that single client: nothing else in this service calls out.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@EnableConfigurationProperties(ProductProperties.class)
public class ProductServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(ProductServiceApplication.class, args);
  }
}
