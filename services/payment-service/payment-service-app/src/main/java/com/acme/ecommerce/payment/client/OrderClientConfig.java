package com.acme.ecommerce.payment.client;

import com.acme.ecommerce.payment.config.InternalTokenFilter;
import com.acme.ecommerce.payment.config.PaymentProperties;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/** Feign config scoped to {@link OrderFeignClient} (deliberately not a @Configuration). */
public class OrderClientConfig {

  @Bean
  public RequestInterceptor internalTokenInterceptor(PaymentProperties properties) {
    return template -> template.header(InternalTokenFilter.HEADER, properties.getInternalToken());
  }
}
