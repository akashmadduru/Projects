package com.acme.ecommerce.promotion.client;

import com.acme.ecommerce.promotion.config.PromotionProperties;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/** Per-client Feign config (not a @Configuration): adds X-Internal-Token. */
public class InternalFeignConfig {

  @Bean
  public RequestInterceptor internalTokenInterceptor(PromotionProperties properties) {
    return template -> template.header("X-Internal-Token", properties.getInternalToken());
  }
}
