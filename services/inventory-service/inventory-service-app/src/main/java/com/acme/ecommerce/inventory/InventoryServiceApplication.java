package com.acme.ecommerce.inventory;

import com.acme.ecommerce.inventory.config.InventoryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * inventory-service entry point.
 *
 * <p>Blocking Spring MVC on VIRTUAL THREADS ({@code spring.threads.virtual.enabled=true}), not
 * WebFlux - the same call product-service and auth-service made, for the same reason. Each request
 * here is a single indexed query behind an ordinary request/response contract; virtual threads give
 * the concurrency a reactive stack would without any of its comprehensibility cost.
 *
 * <p>Registers with Eureka under {@code spring.application.name = inventory-service}, which Eureka
 * uppercases to {@code INVENTORY-SERVICE}. THAT IS THE SERVICE ID THE GATEWAY'S ROUTE TABLE TARGETS
 * ({@code lb://INVENTORY-SERVICE}). Rename the application and the route stops resolving -
 * silently, because a route whose target is unregistered is indistinguishable from one whose target
 * is down.
 *
 * <p>NO {@code @EnableFeignClients}. Nothing in this service calls anything outbound yet; the Feign
 * starter is on the classpath only so common-web's header-relay interceptor registers itself.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableConfigurationProperties(InventoryProperties.class)
public class InventoryServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(InventoryServiceApplication.class, args);
  }
}
