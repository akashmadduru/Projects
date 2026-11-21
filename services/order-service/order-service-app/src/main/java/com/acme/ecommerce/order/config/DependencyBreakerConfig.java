package com.acme.ecommerce.order.config;

import com.acme.ecommerce.order.client.DependencyBreakerCapability;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link DependencyBreakerCapability}: Spring Cloud OpenFeign applies every {@code
 * feign.Capability} bean to every client. {@code order.dependency-breakers.enabled=false} turns it
 * off.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = "order.dependency-breakers.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DependencyBreakerConfig {

  @Bean
  DependencyBreakerCapability dependencyBreakerCapability() {
    return new DependencyBreakerCapability(DependencyBreakerCapability.defaultRegistry());
  }
}
