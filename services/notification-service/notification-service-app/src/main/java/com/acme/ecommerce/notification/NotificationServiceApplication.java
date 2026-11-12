package com.acme.ecommerce.notification;

import com.acme.ecommerce.notification.config.NotificationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/** notification-service entry point: registers with Eureka as {@code notification-service}. */
@SpringBootApplication
@EnableDiscoveryClient
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(NotificationServiceApplication.class, args);
  }
}
