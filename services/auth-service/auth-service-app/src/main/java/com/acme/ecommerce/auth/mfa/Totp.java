package com.acme.ecommerce.auth.mfa;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 TOTP (SHA-1, 6 digits, 30 s) and RFC 4648 base32, implemented in-house (no new lib). */
public final class Totp {

  public static final int PERIOD_SECONDS = 30;
  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final SecureRandom RANDOM = new SecureRandom();

  private Totp() {}

  public static byte[] newSecret() {
    byte[] secret = new byte[20];
    RANDOM.nextBytes(secret);
    return secret;
  }

  public static long stepAt(long epochSeconds) {
    return epochSeconds / PERIOD_SECONDS;
  }

  public static String code(byte[] secret, long step) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret, "HmacSHA1"));
      byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      int offset = hash[hash.length - 1] & 0x0f;
      int binary =
          ((hash[offset] & 0x7f) << 24)
              | ((hash[offset + 1] & 0xff) << 16)
              | ((hash[offset + 2] & 0xff) << 8)
              | (hash[offset + 3] & 0xff);
      return String.format("%06d", binary % 1_000_000);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA1 unavailable", e);
    }
  }

  /**
   * Returns the matching time-step within +/-1 of {@code nowStep} that is greater than {@code
   * lastUsedStep}, or -1 when the code is wrong or a replay. Constant-time per comparison.
   */
  public static long match(byte[] secret, String submitted, long nowStep, long lastUsedStep) {
    if (submitted == null || !submitted.matches("\\d{6}")) {
      return -1;
    }
    long found = -1;
    for (long step = nowStep - 1; step <= nowStep + 1; step++) {
      String expected = code(secret, step);
      boolean equal = java.security.MessageDigest.isEqual(expected.getBytes(), submitted.getBytes());
      if (equal && step > lastUsedStep) {
        found = step;
      }
    }
    return found;
  }

  public static String base32(byte[] data) {
    StringBuilder out = new StringBuilder();
    int buffer = 0;
    int bits = 0;
    for (byte b : data) {
      buffer = (buffer << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) {
        out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
        bits -= 5;
      }
    }
    if (bits > 0) {
      out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
    }
    return out.toString();
  }
}
