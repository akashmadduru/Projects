package com.acme.ecommerce.user.client;

import com.acme.ecommerce.user.config.InternalSecurityConfig;
import feign.RequestInterceptor;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** payment-service internal create (purpose MEMBERSHIP). Response is passed through untouched. */
@FeignClient(
    name = "payment-service",
    contextId = "userPaymentClient",
    url = "${services.payment.url:}",
    configuration = PaymentFeignClient.InternalFeignConfig.class)
public interface PaymentFeignClient {

  /** orderId carries the same value as referenceId so ORDER-only builds still accept the call. */
  record CreatePayment(
      UUID orderId,
      UUID referenceId,
      String purpose,
      UUID userId,
      BigDecimal amount,
      String currency,
      String method) {}

  @PostMapping("/internal/payments")
  Map<String, Object> create(@RequestBody CreatePayment request);

  class InternalFeignConfig {
    @Bean
    public RequestInterceptor userPaymentTokenInterceptor(
        @Value("${user.internal-token:}") String token) {
      return template -> template.header(InternalSecurityConfig.HEADER, token);
    }
  }
}
