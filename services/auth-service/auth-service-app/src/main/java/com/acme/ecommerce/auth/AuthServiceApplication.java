package com.acme.ecommerce.auth;

import com.acme.ecommerce.auth.config.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * auth-service entry point.
 *
 * <p>Blocking Spring MVC on VIRTUAL THREADS ({@code spring.threads.virtual.enabled=true}), not
 * WebFlux. Every request here is a short burst of blocking I/O - one or two indexed queries, one
 * Argon2 hash - behind an ordinary request/response contract. Virtual threads deliver the same
 * "thousands of concurrent requests without thousands of platform threads" scaling that a reactive
 * stack would, while leaving the code as plain imperative Java that {@code @Transactional},
 * debuggers, thread dumps and stack traces all understand. WebFlux would buy nothing here and cost
 * a great deal of comprehensibility; the gateway is reactive because it is a proxy, and that is a
 * genuinely different workload.
 *
 * <p>Note that Argon2 verification is CPU-bound and is NOT helped by virtual threads - see {@code
 * PasswordConfig} for the capacity consequence.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableConfigurationProperties(AuthProperties.class)
@EnableScheduling
@org.springframework.cloud.openfeign.EnableFeignClients
public class AuthServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(AuthServiceApplication.class, args);
  }
}
