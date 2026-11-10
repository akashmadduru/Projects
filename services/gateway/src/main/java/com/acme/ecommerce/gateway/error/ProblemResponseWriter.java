package com.acme.ecommerce.gateway.error;

import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.gateway.filter.CorrelationIdWebFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Writes an RFC 9457 {@code application/problem+json} document straight to the reactive response.
 *
 * <p>Every failure the gateway itself produces goes through here, so that a client sees ONE error
 * shape from the platform - the same one auth-service's {@code ProblemResponseWriter} emits, member
 * for member. Without a single writer the edge ends up with four dialects: Spring Security's empty
 * 401 body, the gateway's default JSON error map, the circuit-breaker fallback, and whatever a
 * downstream leaked.
 *
 * <p>THE DOWNSTREAM'S BODY IS NEVER FORWARDED ON A GATEWAY-GENERATED FAILURE. A connection-refused
 * stack trace or a Netty exception message tells an attacker the internal hostnames, ports and
 * framework versions behind the edge, and tells a legitimate client nothing it can act on.
 */
@Component
public class ProblemResponseWriter {

  private final ObjectMapper objectMapper;
  private final TimeProvider timeProvider;

  public ProblemResponseWriter(ObjectMapper objectMapper, TimeProvider timeProvider) {
    this.objectMapper = objectMapper;
    this.timeProvider = timeProvider;
  }

  /** Writes {@code errorCode}'s default status and title. */
  public Mono<Void> write(ServerWebExchange exchange, ErrorCode errorCode) {
    return write(exchange, errorCode, errorCode.title(), Map.of());
  }

  /** Writes {@code errorCode} with an explicit detail and extra members. */
  public Mono<Void> write(
      ServerWebExchange exchange,
      ErrorCode errorCode,
      String detail,
      Map<String, Object> extensions) {

    if (exchange.getResponse().isCommitted()) {
      // Nothing useful left to say - the client already has a status line and headers.
      return Mono.empty();
    }

    HttpStatus status = HttpStatus.valueOf(errorCode.status());
    exchange.getResponse().setStatusCode(status);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", errorCode.type());
    body.put("title", errorCode.title());
    body.put("status", errorCode.status());
    body.put("detail", detail == null || detail.isBlank() ? errorCode.title() : detail);
    body.put("instance", exchange.getRequest().getPath().value());
    body.put("code", errorCode.code());
    body.put("correlationId", CorrelationIdWebFilter.correlationId(exchange));
    body.put("timestamp", timeProvider.now().toString());
    body.putAll(extensions);

    DataBuffer buffer;
    try {
      buffer = exchange.getResponse().bufferFactory().wrap(objectMapper.writeValueAsBytes(body));
    } catch (JsonProcessingException unserialisable) {
      // A map of strings and ints cannot realistically fail to serialise; if it somehow does,
      // a bodiless status is still better than a 500 raised while handling an error.
      buffer =
          exchange
              .getResponse()
              .bufferFactory()
              .wrap(("{\"status\":" + errorCode.status() + "}").getBytes(StandardCharsets.UTF_8));
    }

    return exchange.getResponse().writeWith(Mono.just(buffer));
  }
}
