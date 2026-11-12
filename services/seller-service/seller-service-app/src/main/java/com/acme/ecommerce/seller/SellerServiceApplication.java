package com.acme.ecommerce.seller;

import com.acme.ecommerce.seller.config.SellerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/** seller-service entry point (Eureka id {@code seller-service}, gateway {@code lb://SELLER-SERVICE}). */
@SpringBootApplication
@EnableDiscoveryClient
@EnableConfigurationProperties(SellerProperties.class)
@EnableFeignClients
@EnableScheduling
public class SellerServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(SellerServiceApplication.class, args);
  }
}
