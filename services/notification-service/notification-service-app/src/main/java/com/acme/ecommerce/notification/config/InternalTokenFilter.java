package com.acme.ecommerce.notification.config;

import com.acme.ecommerce.common.core.correlation.CorrelationIdHolder;
import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.notification.api.NotificationClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards {@code /internal/**} with the shared {@code X-Internal-Token}. Constant-time compare;
 * missing or wrong token (or no configured token at all) is 401 INTERNAL_AUTH_FAILED. The gateway
 * never routes these paths, so this is the only line of defence on the compose network.
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

  private final byte[] expected;

  public InternalTokenFilter(NotificationProperties properties) {
    this.expected = properties.internalToken().getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/internal/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String presented = request.getHeader(NotificationClient.INTERNAL_TOKEN_HEADER);
    boolean ok =
        expected.length > 0
            && presented != null
            && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    if (ok) {
      chain.doFilter(request, response);
      return;
    }
    ErrorCode code = ErrorCode.INTERNAL_AUTH_FAILED;
    response.setStatus(code.status());
    response.setContentType("application/problem+json");
    response.setCharacterEncoding("UTF-8");
    response
        .getWriter()
        .write(
            "{\"type\":\""
                + code.type()
                + "\",\"title\":\""
                + code.title()
                + "\",\"status\":"
                + code.status()
                + ",\"code\":\""
                + code.code()
                + "\",\"correlationId\":\""
                + CorrelationIdHolder.getOrGenerate()
                + "\"}");
  }
}
