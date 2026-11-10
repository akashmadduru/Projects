package com.acme.ecommerce.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Standalone Netflix Eureka service registry.
 *
 * <p>This application intentionally contains no other code. It is a single-purpose infrastructure
 * component: it accepts registrations and renewals from platform services and serves the registry
 * back to them. Anything resembling business logic, persistence or a public HTTP route belongs in a
 * different module.
 *
 * <p>It runs as a <em>standalone</em> (single-node, non-peered) registry - see {@code
 * application.yml} for why {@code register-with-eureka} and {@code fetch-registry} are both
 * disabled, and for the dev-vs-prod tuning trade-offs.
 */
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(EurekaServerApplication.class, args);
  }
}
