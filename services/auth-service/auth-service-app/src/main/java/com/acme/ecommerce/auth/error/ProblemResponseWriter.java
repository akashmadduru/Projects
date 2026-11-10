package com.acme.ecommerce.auth.error;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.common.core.correlation.CorrelationIdHolder;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Writes an RFC 9457 problem document straight to the servlet response.
 *
 * <p>Needed because two important rejections happen OUTSIDE Spring MVC and therefore never reach a
 * {@code @RestControllerAdvice}:
 *
 * <ul>
 *   <li>the rate-limit filter, which runs before the dispatcher servlet;
 *   <li>Spring Security's {@code AuthenticationEntryPoint} / {@code AccessDeniedHandler}, which
 *       fire inside the filter chain.
 * </ul>
 *
 * <p>Without this, those two paths would return Spring's default HTML error page or an empty body,
 * and a client would have to parse three different error formats from one service.
 */
@Component
public class ProblemResponseWriter {

  private final ObjectMapper objectMapper;
  private final TimeProvider timeProvider;

  public ProblemResponseWriter(ObjectMapper objectMapper, TimeProvider timeProvider) {
    this.objectMapper = objectMapper;
    this.timeProvider = timeProvider;
  }

  /** Serialises {@code code} as a problem document with the same members MVC would produce. */
  public void write(HttpServletRequest request, HttpServletResponse response, AuthErrorCode code)
      throws IOException {

    if (response.isCommitted()) {
      return;
    }
    response.reset();
    response.setStatus(code.status());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", code.type());
    body.put("title", code.title());
    body.put("status", code.status());
    body.put("detail", code.title());
    body.put("instance", request.getRequestURI());
    body.put("code", code.code());
    body.put("correlationId", CorrelationIdHolder.getOrGenerate());
    body.put("timestamp", timeProvider.now().toString());

    objectMapper.writeValue(response.getOutputStream(), body);
  }
}
