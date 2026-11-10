package com.acme.ecommerce.gateway;

import com.acme.ecommerce.gateway.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The edge application.
 *
 * <p>REACTIVE, and not because reactive is fashionable. A gateway does essentially no CPU work and
 * spends its entire life waiting on sockets it does not own; with a thread-per-request model, one
 * slow downstream consumes the whole pool and takes every unrelated route down with it. Spring
 * Cloud Gateway's routing, load-balancing and proxying filters are also reactive-only in the
 * 2025.1.x train, so this is not a per-module preference - it is the module's stack.
 *
 * <p>auth-service, by contrast, is blocking MVC on virtual threads. That is not an inconsistency:
 * it is a short-lived, blocking, request/response workload with a database behind it, which is
 * exactly what virtual threads are for. See its {@code build.gradle} for that reasoning.
 *
 * <p>{@code @EnableConfigurationProperties} rather than {@code @ConfigurationPropertiesScan}: this
 * module has exactly one properties class, and naming it here means a reader can see the module's
 * full configuration surface from the entry point.
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
