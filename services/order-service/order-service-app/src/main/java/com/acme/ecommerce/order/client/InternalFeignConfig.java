package com.acme.ecommerce.order.client;

import com.acme.ecommerce.order.config.OrderProperties;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/**
 * Per-client Feign configuration (deliberately NOT a {@code @Configuration}, so it is not
 * component-scanned into the global context): adds X-Internal-Token to calls made through the
 * clients that reference it, and nothing else.
 */
public class InternalFeignConfig {

  @Bean
  public RequestInterceptor internalTokenInterceptor(OrderProperties properties) {
    return template -> template.header("X-Internal-Token", properties.getInternal().getToken());
  }
}
