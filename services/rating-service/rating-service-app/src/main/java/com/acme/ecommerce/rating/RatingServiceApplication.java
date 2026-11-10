package com.acme.ecommerce.rating;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Rating Service application entry point.
 *
 * Provides product rating and review functionality with Kafka event integration.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class RatingServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(RatingServiceApplication.class, args);
  }
}
