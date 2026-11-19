package com.acme.ecommerce.auth.web;

import com.acme.ecommerce.auth.token.DeviceContext;
import com.acme.ecommerce.common.web.net.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Extracts the client's IP and user-agent from a request.
 *
 * <p>The IP comes from {@link ClientIp}: the gateway-set {@code X-Internal-Client-Ip} header when
 * present, else the socket peer. {@code X-Forwarded-For} is never read - the gateway strips it and
 * any caller could forge it, which would silently defeat the per-IP throttle and poison the
 * forensic record on every refresh token.
 */
@Component
public class RequestDeviceContext {

  /** User-agent plus source IP, truncated to what the database can store. */
  public DeviceContext from(HttpServletRequest request) {
    return new DeviceContext(request.getHeader(HttpHeaders.USER_AGENT), clientIp(request))
        .truncated();
  }

  /** The address the rate limiter buckets on. Never null. */
  public String clientIp(HttpServletRequest request) {
    return ClientIp.resolveAsString(request);
  }
}
