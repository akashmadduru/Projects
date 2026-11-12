package com.acme.ecommerce.auth.client;

import com.acme.ecommerce.notification.api.NotificationClient;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/**
 * Per-client Feign configuration. Deliberately NOT annotated {@code @Configuration}: it must not be
 * component-scanned into the main context, or the interceptor would send the internal token on
 * every Feign client the service ever has.
 */
public class NotificationFeignConfig {

  @Bean
  public RequestInterceptor internalTokenInterceptor(
      @Value("${notification.internal-token:}") String token) {
    return template -> template.header(NotificationClient.INTERNAL_TOKEN_HEADER, token);
  }
}
