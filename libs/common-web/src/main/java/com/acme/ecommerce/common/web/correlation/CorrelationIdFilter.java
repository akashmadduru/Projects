package com.acme.ecommerce.common.web.correlation;

import com.acme.ecommerce.common.core.correlation.CorrelationIdHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds a correlation id to the request thread and the SLF4J {@link MDC} for the duration of the
 * request, then echoes it back on the response.
 *
 * <p>Accepts an inbound {@code X-Correlation-Id} (normally set by the gateway) so a single id spans
 * every hop of a request; generates one when absent, so a request that bypasses the gateway is
 * still traceable.
 *
 * <p>Registered at {@link Ordered#HIGHEST_PRECEDENCE} + 10: it must run before the security filter
 * chain, otherwise authentication failures - the ones you most need to trace - would be logged
 * without an id.
 *
 * <p>INPUT VALIDATION: the inbound value is client-controlled and flows into log lines and a
 * response header. It is length-capped and character-restricted to prevent log forging (CRLF
 * injection) and response-header splitting. A value that fails the check is discarded, not
 * sanitised, so a malicious id can never be silently half-honoured.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

  /** Run before Spring Security (which defaults to -100). */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

  private static final int MAX_LENGTH = 64;

  /** Conservative allow-list: UUIDs, trace ids and similar opaque tokens. */
  private static final Pattern SAFE_VALUE = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String correlationId = sanitize(request.getHeader(CorrelationIdHolder.HEADER_NAME));

    // set(null) generates a fresh id, so this covers both the absent and rejected cases.
    String effectiveId = CorrelationIdHolder.set(correlationId);
    MDC.put(CorrelationIdHolder.MDC_KEY, effectiveId);

    // Set the response header before the chain runs: a committed response (streaming, or an error
    // written by a downstream filter) can no longer accept headers afterwards.
    response.setHeader(CorrelationIdHolder.HEADER_NAME, effectiveId);

    try {
      filterChain.doFilter(request, response);
    } finally {
      // MANDATORY on pooled request threads: without this the id leaks into the next request
      // handled by this thread and corrupts the log trail.
      MDC.remove(CorrelationIdHolder.MDC_KEY);
      CorrelationIdHolder.clear();
    }
  }

  private static String sanitize(String candidate) {
    if (candidate == null) {
      return null;
    }
    String trimmed = candidate.trim();
    if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
      return null;
    }
    return SAFE_VALUE.matcher(trimmed).matches() ? trimmed : null;
  }
}
