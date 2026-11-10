package com.acme.ecommerce.auth.web;

import com.acme.ecommerce.auth.config.AuthProperties;
import com.acme.ecommerce.auth.token.DeviceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Extracts the client's IP and user-agent from a request.
 *
 * <p>{@code X-Forwarded-For} IS NOT TRUSTED BY DEFAULT. Any client can send that header, so reading
 * it on a service reachable directly means an attacker chooses their own apparent source address -
 * which silently defeats the per-IP rate limit and poisons the forensic record on every refresh
 * token. It is only correct behind a proxy that OVERWRITES (not appends to) the header, which is
 * why it is behind {@code auth.rate-limit.trust-forwarded-for} rather than on by default.
 *
 * <p>When trusted, the FIRST entry is used: proxies append, so the leftmost value is the original
 * client and everything after it is infrastructure.
 */
@Component
public class RequestDeviceContext {

  private static final String FORWARDED_FOR = "X-Forwarded-For";

  private final boolean trustForwardedFor;

  public RequestDeviceContext(AuthProperties properties) {
    this.trustForwardedFor = properties.getRateLimit().trustForwardedFor();
  }

  /** User-agent plus source IP, truncated to what the database can store. */
  public DeviceContext from(HttpServletRequest request) {
    return new DeviceContext(request.getHeader(HttpHeaders.USER_AGENT), clientIp(request))
        .truncated();
  }

  /** The address the rate limiter buckets on. Never null. */
  public String clientIp(HttpServletRequest request) {
    if (trustForwardedFor) {
      String forwarded = request.getHeader(FORWARDED_FOR);
      if (forwarded != null && !forwarded.isBlank()) {
        int comma = forwarded.indexOf(',');
        String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        if (!first.isEmpty()) {
          return first;
        }
      }
    }
    String remote = request.getRemoteAddr();
    return remote == null ? "unknown" : remote;
  }
}
