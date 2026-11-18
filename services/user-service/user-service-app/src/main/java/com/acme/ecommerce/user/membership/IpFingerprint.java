package com.acme.ecommerce.user.membership;

import com.acme.ecommerce.user.config.UserProperties;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Keyed hash of an IP (HMAC-SHA256, 64 lowercase hex). The raw address never leaves this class and
 * is never logged. A blank salt yields a random per-process key: hashes then only match in-process,
 * which can miss a match but never refuse wrongly.
 */
@Component
public class IpFingerprint {

  private static final Logger log = LoggerFactory.getLogger(IpFingerprint.class);
  private static final String ALGORITHM = "HmacSHA256";

  private final SecretKeySpec key;

  @Autowired
  public IpFingerprint(UserProperties properties) {
    this(properties.getReferral().getIpHashSalt());
  }

  IpFingerprint(String salt) {
    byte[] keyBytes;
    if (salt == null || salt.isBlank()) {
      keyBytes = new byte[32];
      new SecureRandom().nextBytes(keyBytes);
      log.warn(
          "user.referral.ip-hash-salt is blank: using a random per-process key, so referral IP"
              + " matches only work within this process. Set USER_REFERRAL_IP_HASH_SALT in production.");
    } else {
      keyBytes = salt.getBytes(StandardCharsets.UTF_8);
    }
    this.key = new SecretKeySpec(keyBytes, ALGORITHM);
  }

  public String hash(InetAddress address) {
    byte[] bytes = address.getAddress();
    byte[] message = new byte[bytes.length + 1];
    message[0] = (byte) (address instanceof Inet6Address ? 6 : 4);
    System.arraycopy(bytes, 0, message, 1, bytes.length);
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return HexFormat.of().formatHex(mac.doFinal(message));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }
}
