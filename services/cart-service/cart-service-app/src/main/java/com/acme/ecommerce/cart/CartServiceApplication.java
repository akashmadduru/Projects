package com.acme.ecommerce.cart;

import com.acme.ecommerce.cart.config.CartProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * cart-service entry point: blocking Spring MVC on virtual threads, registers with Eureka as {@code
 * cart-service} (the gateway targets {@code lb://CART-SERVICE}).
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableConfigurationProperties(CartProperties.class)
@EnableFeignClients
@EnableScheduling
public class CartServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(CartServiceApplication.class, args);
  }
}
