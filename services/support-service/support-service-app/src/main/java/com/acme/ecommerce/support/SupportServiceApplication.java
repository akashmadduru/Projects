package com.acme.ecommerce.support;

import com.acme.ecommerce.support.config.SupportProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/** support-service entry point (Eureka id {@code support-service}, gateway {@code lb://SUPPORT-SERVICE}). */
@SpringBootApplication
@EnableDiscoveryClient
@EnableConfigurationProperties(SupportProperties.class)
@EnableFeignClients
@EnableScheduling
public class SupportServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(SupportServiceApplication.class, args);
  }
}
