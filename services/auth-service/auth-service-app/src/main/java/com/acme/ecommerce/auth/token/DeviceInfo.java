package com.acme.ecommerce.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Coarse device description + fingerprint (UA family + /24 IP, hashed) for alerts and sessions. */
public final class DeviceInfo {

  private DeviceInfo() {}

  public static String browser(String ua) {
    if (ua == null) {
      return "Unknown browser";
    }
    String u = ua.toLowerCase();
    if (u.contains("edg/")) return "Edge";
    if (u.contains("opr/") || u.contains("opera")) return "Opera";
    if (u.contains("firefox")) return "Firefox";
    if (u.contains("chrome") || u.contains("crios")) return "Chrome";
    if (u.contains("safari")) return "Safari";
    if (u.contains("curl")) return "curl";
    if (u.contains("node") || u.contains("undici")) return "Node client";
    return "Other browser";
  }

  public static String os(String ua) {
    if (ua == null) {
      return "unknown OS";
    }
    String u = ua.toLowerCase();
    if (u.contains("windows")) return "Windows";
    if (u.contains("android")) return "Android";
    if (u.contains("iphone") || u.contains("ipad") || u.contains("ios")) return "iOS";
    if (u.contains("mac os") || u.contains("macintosh")) return "macOS";
    if (u.contains("linux")) return "Linux";
    return "unknown OS";
  }

  public static String describe(String ua) {
    return browser(ua) + " on " + os(ua);
  }

  /** "203.0.113.x" style approximate location marker; never the full address. */
  public static String maskedIp(String ip) {
    if (ip == null || ip.isBlank() || "unknown".equals(ip)) {
      return "unknown";
    }
    return prefix(ip) + (ip.contains(":") ? "::" : ".x");
  }

  public static String fingerprint(String ua, String ip) {
    try {
      String raw = browser(ua) + "|" + os(ua) + "|" + (ip == null ? "unknown" : prefix(ip));
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String prefix(String ip) {
    if (ip.contains(":")) {
      String[] parts = ip.split(":");
      return String.join(":", java.util.Arrays.copyOf(parts, Math.min(3, parts.length)));
    }
    int idx = ip.lastIndexOf('.');
    return idx > 0 ? ip.substring(0, idx) : ip;
  }
}
