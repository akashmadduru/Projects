package com.acme.ecommerce.rating;

import com.acme.ecommerce.rating.config.RatingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * rating-service entry point: blocking Spring MVC on virtual threads, registers with Eureka as
 * {@code rating-service} (the gateway targets {@code lb://RATING-SERVICE}). Feign clients call
 * order-service (purchase verification) and user-service (display name).
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@EnableConfigurationProperties(RatingProperties.class)
public class RatingServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(RatingServiceApplication.class, args);
  }
}
