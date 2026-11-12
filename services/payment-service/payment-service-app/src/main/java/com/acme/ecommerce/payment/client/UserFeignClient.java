package com.acme.ecommerce.payment.client;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Reports MEMBERSHIP payment outcomes to user-service (internal, X-Internal-Token). */
@FeignClient(
    name = "user-service",
    contextId = "userMembershipClient",
    url = "${services.user.url:}",
    configuration = OrderClientConfig.class)
public interface UserFeignClient {

  record MembershipPaymentResult(UUID referenceId, String status, UUID paymentId) {}

  @PostMapping("/internal/users/membership/payment-result")
  void membershipPaymentResult(@RequestBody MembershipPaymentResult body);
}
