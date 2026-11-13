package com.acme.ecommerce.auth.service;

import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.auth.domain.KnownDevice;
import com.acme.ecommerce.auth.repository.KnownDeviceRepository;
import com.acme.ecommerce.auth.repository.UserAccountRepository;
import com.acme.ecommerce.auth.token.DeviceContext;
import com.acme.ecommerce.auth.token.DeviceInfo;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.notification.api.dto.EmailTemplate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emails the user when a session starts from a device fingerprint (UA family + /24, hashed) not
 * seen before. The very first device an account ever logs in from is recorded silently (nothing to
 * compare against). Runs in its own transaction and never throws into the login path.
 */
@Service
public class LoginAlertService {

  private static final Logger log = LoggerFactory.getLogger(LoginAlertService.class);
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'UTC'").withZone(ZoneOffset.UTC);

  private final KnownDeviceRepository devices;
  private final UserAccountRepository users;
  private final NotificationGateway notifications;
  private final TimeProvider time;
  private final String storefrontBaseUrl;

  public LoginAlertService(
      KnownDeviceRepository devices,
      UserAccountRepository users,
      NotificationGateway notifications,
      TimeProvider time,
      AuthProperties properties) {
    this.devices = devices;
    this.users = users;
    this.notifications = notifications;
    this.time = time;
    this.storefrontBaseUrl = properties.storefront().baseUrl();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onSessionStarted(UUID userId, String email, DeviceContext device) {
    try {
      String ua = device == null ? null : device.userAgent();
      String ip = device == null ? null : device.ipAddress();
      Instant now = time.now();
      String fingerprint = DeviceInfo.fingerprint(ua, ip);
      if (devices.touch(userId, fingerprint, now) > 0) {
        return;
      }
      boolean hadDevices = devices.countByUserId(userId) > 0;
      devices.save(new KnownDevice(userId, fingerprint, now));
      if (!hadDevices) {
        return;
      }
      String name = users.findById(userId).map(u -> u.getDisplayName()).orElse("there");
      notifications.sendEmail(
          email,
          EmailTemplate.LOGIN_ALERT,
          Map.of(
              "name", name == null ? "there" : name,
              "device", DeviceInfo.describe(ua),
              "ip", DeviceInfo.maskedIp(ip),
              "time", WHEN.format(now),
              "link", storefrontBaseUrl + "/account/security"));
      log.info("Login alert sent for userId={}", userId);
    } catch (RuntimeException e) {
      log.warn("Login alert failed for userId={}: {}", userId, e.toString());
    }
  }
}
