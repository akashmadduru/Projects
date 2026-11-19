package com.acme.ecommerce.auth.service;

import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.notification.api.dto.EmailTemplate;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Tells the owner of a verified phone that someone tried to add it to another account.
 *
 * <p>At most one email per owner per window: an atomic {@code SET NX PX} on the owner id (no phone
 * number is stored). FAIL-CLOSED for the notice only: if Redis is down the notice is skipped, since
 * an unbounded email stream is a worse outcome than one missed notice (the attempt already failed).
 * Runs off the request thread and never throws, so the add-phone response is the same for taken and
 * free numbers.
 */
@Service
public class PhoneClaimNotifier {

  private static final Logger log = LoggerFactory.getLogger(PhoneClaimNotifier.class);
  private static final long WARN_EVERY_MILLIS = 30_000;

  private final UserAccountRepository users;
  private final StringRedisTemplate redis;
  private final NotificationGateway notifications;
  private final Duration window;
  private final String securityLink;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicLong lastWarnMillis = new AtomicLong(Long.MIN_VALUE);

  public PhoneClaimNotifier(
      UserAccountRepository users,
      StringRedisTemplate redis,
      NotificationGateway notifications,
      AuthProperties properties,
      @Value("${auth.phone.claim-notice-window:24h}") Duration window) {
    this.users = users;
    this.redis = redis;
    this.notifications = notifications;
    this.window = window;
    this.securityLink = properties.storefront().baseUrl() + "/account/security";
  }

  /** Someone tried to claim {@code e164}; notify its verified owner at most once per window. */
  public void attempted(String e164) {
    executor.execute(() -> notifyOwner(e164));
  }

  void notifyOwner(String e164) {
    try {
      users
          .findWithRolesByPhoneAndPhoneVerifiedTrue(e164)
          .ifPresent(
              owner -> {
                Boolean first =
                    redis
                        .opsForValue()
                        .setIfAbsent("auth:phone-claim-notice:" + owner.getId(), "1", window);
                if (Boolean.TRUE.equals(first)) {
                  notifications.sendEmail(
                      owner.getEmail(),
                      EmailTemplate.PHONE_CLAIM_ATTEMPT,
                      Map.of(
                          "name",
                          owner.getDisplayName(),
                          "phoneTail",
                          e164.substring(Math.max(0, e164.length() - 4)),
                          "link",
                          securityLink));
                }
              });
    } catch (RuntimeException e) {
      warnThrottled(e);
    }
  }

  private void warnThrottled(RuntimeException e) {
    long now = System.currentTimeMillis();
    long last = lastWarnMillis.get();
    if ((last == Long.MIN_VALUE || now - last >= WARN_EVERY_MILLIS)
        && lastWarnMillis.compareAndSet(last, now)) {
      log.warn("Phone claim notice skipped: {}", e.getClass().getSimpleName());
    }
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
  }
}
