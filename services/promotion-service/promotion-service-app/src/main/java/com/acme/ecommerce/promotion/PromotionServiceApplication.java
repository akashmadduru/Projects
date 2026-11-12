package com.acme.ecommerce.promotion;

import com.acme.ecommerce.promotion.config.PromotionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/** promotion-service entry point (Eureka name {@code promotion-service}): coupons. */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@EnableConfigurationProperties(PromotionProperties.class)
public class PromotionServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(PromotionServiceApplication.class, args);
  }
}
