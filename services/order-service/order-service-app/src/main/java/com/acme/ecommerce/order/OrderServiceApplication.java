package com.acme.ecommerce.order;

import com.acme.ecommerce.order.config.OrderProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * order-service entry point: blocking Spring MVC on virtual threads, registers with Eureka as
 * {@code order-service} (the gateway targets {@code lb://ORDER-SERVICE}).
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableConfigurationProperties(OrderProperties.class)
@EnableFeignClients
public class OrderServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrderServiceApplication.class, args);
  }
}
