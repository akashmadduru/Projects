package com.acme.ecommerce.payment.client;

import com.acme.ecommerce.payment.api.dto.PaymentResultRequest;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Reports payment outcomes to order-service. Set services.order.url only to bypass discovery. */
@FeignClient(
    name = "order-service",
    url = "${services.order.url:}",
    configuration = OrderClientConfig.class)
public interface OrderFeignClient {

  @PostMapping("/internal/orders/{orderId}/payment-result")
  void paymentResult(@PathVariable("orderId") UUID orderId, @RequestBody PaymentResultRequest body);
}
