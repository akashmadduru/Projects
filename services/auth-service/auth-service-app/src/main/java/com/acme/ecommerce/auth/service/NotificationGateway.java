package com.acme.ecommerce.auth.service;

import com.acme.ecommerce.auth.client.NotificationFeignClient;
import com.acme.ecommerce.notification.api.dto.EmailRequest;
import com.acme.ecommerce.notification.api.dto.EmailTemplate;
import com.acme.ecommerce.notification.api.dto.SmsRequest;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fire-and-forget bridge to notification-service.
 *
 * <p>ASYNC AND FAIL-OPEN, on purpose: a slow or dead notification-service must never break
 * register / forgot-password, and the send must not be on the request's critical path (otherwise
 * the response time of "forgot password" would reveal whether an account exists). Failures are
 * logged WITHOUT payloads (they contain tokens/codes). Tech debt: no retry or outbox.
 */
@Service
public class NotificationGateway {

  private static final Logger log = LoggerFactory.getLogger(NotificationGateway.class);

  private final NotificationFeignClient client;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public NotificationGateway(NotificationFeignClient client) {
    this.client = client;
  }

  /** Sends a templated email in the background. */
  public void sendEmail(String to, EmailTemplate template, Map<String, String> data) {
    executor.execute(
        () -> {
          try {
            client.sendEmail(new EmailRequest(to, template, data));
          } catch (RuntimeException e) {
            log.warn("Notification email {} failed: {}", template, e.getClass().getSimpleName());
          }
        });
  }

  /** Sends an SMS in the background. */
  public void sendSms(String to, String text) {
    executor.execute(
        () -> {
          try {
            client.sendSms(new SmsRequest(to, text));
          } catch (RuntimeException e) {
            log.warn("Notification SMS failed: {}", e.getClass().getSimpleName());
          }
        });
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
  }
}
