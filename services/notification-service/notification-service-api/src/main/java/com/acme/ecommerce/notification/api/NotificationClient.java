package com.acme.ecommerce.notification.api;

import com.acme.ecommerce.notification.api.dto.EmailRequest;
import com.acme.ecommerce.notification.api.dto.SmsRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Declarative client for notification-service's internal endpoints. Not annotated
 * {@code @FeignClient}: the consumer supplies that, plus the {@code X-Internal-Token} interceptor.
 */
public interface NotificationClient {

  String BASE_PATH = "/internal/notifications";
  String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  @PostMapping(BASE_PATH + "/email")
  void sendEmail(@RequestBody EmailRequest request);

  @PostMapping(BASE_PATH + "/sms")
  void sendSms(@RequestBody SmsRequest request);
}
