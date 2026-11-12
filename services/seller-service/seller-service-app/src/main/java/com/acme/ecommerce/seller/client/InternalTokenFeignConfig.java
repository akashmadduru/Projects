package com.acme.ecommerce.seller.client;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/** Not a scanned @Configuration: referenced by the Feign clients that call /internal/**. */
public class InternalTokenFeignConfig {
  @Bean
  public RequestInterceptor sellerInternalTokenInterceptor(
      @Value("${seller.internal-token:}") String token) {
    return template -> template.header("X-Internal-Token", token);
  }
}
