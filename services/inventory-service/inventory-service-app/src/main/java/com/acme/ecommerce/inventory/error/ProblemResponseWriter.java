package com.acme.ecommerce.inventory.error;

import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.core.error.ProblemPayload;
import com.acme.ecommerce.common.core.error.ProblemPayloadFactory;
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
 * <p>Needed because Spring Security's {@code AuthenticationEntryPoint} and {@code
 * AccessDeniedHandler} fire INSIDE the filter chain and therefore never reach a
 * {@code @RestControllerAdvice}. Without this, the single most common error in the platform - an
 * expired access token - would come back from this service as a bodiless 401 with a {@code
 * WWW-Authenticate} header, while every other error it produces is problem+json. A client should
 * never have to parse two error formats from one service.
 *
 * <p>Unlike auth-service's equivalent, this one takes the shared {@link ErrorCode} rather than a
 * service-specific enum: inventory-service has no failure mode the platform catalogue does not
 * already name, and inventing one would only give clients a second vocabulary to learn. Byte-for-
 * byte the same pattern as product-service's copy - new package only, matching this platform's
 * existing per-service duplication convention over pulling this into common-web.
 */
@Component
public class ProblemResponseWriter {

  private final ObjectMapper objectMapper;
  private final ProblemPayloadFactory problemPayloadFactory;

  public ProblemResponseWriter(
      ObjectMapper objectMapper, ProblemPayloadFactory problemPayloadFactory) {
    this.objectMapper = objectMapper;
    this.problemPayloadFactory = problemPayloadFactory;
  }

  /**
   * Serialises {@code errorCode} as a problem document with the same members MVC would produce.
   *
   * @param detail human-readable explanation; never derived from an exception message, because the
   *     underlying {@code JwtException} text quotes the token's claims
   */
  public void write(
      HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode, String detail)
      throws IOException {

    if (response.isCommitted()) {
      return;
    }
    response.reset();
    response.setStatus(errorCode.status());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");

    ProblemPayload payload =
        problemPayloadFactory.create(errorCode, detail, request.getRequestURI());

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", payload.type());
    body.put("title", payload.title());
    body.put("status", payload.status());
    body.put("detail", payload.detail());
    body.put("instance", payload.instance());
    body.put("code", payload.code());
    body.put("correlationId", payload.correlationId());
    body.put("timestamp", payload.timestamp().toString());

    objectMapper.writeValue(response.getOutputStream(), body);
  }
}
