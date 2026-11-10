package com.acme.ecommerce.gateway.error;

import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.gateway.filter.CorrelationIdWebFilter;
import java.io.IOException;
import java.net.ConnectException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Last-resort translation of anything that escapes the routing pipeline into problem+json.
 *
 * <p>Ordered {@value #ORDER} so it wins over Boot's {@code DefaultErrorWebExceptionHandler}
 * (registered at {@code -1}). Without it, the failures a gateway actually experiences - a
 * downstream refusing a connection, a response timeout, an {@code lb://} service with no registered
 * instance - surface as Boot's default error map, which includes the exception's message. That
 * message is routinely "Connection refused: product-service/10.0.3.17:8082", i.e. a free map of the
 * internal network handed to whoever asked.
 */
@Component
@Order(GatewayProblemExceptionHandler.ORDER)
public class GatewayProblemExceptionHandler implements ErrorWebExceptionHandler {

  /** Ahead of Boot's {@code DefaultErrorWebExceptionHandler} at {@code -1}. */
  public static final int ORDER = -2;

  private static final Logger log = LoggerFactory.getLogger(GatewayProblemExceptionHandler.class);

  private final ProblemResponseWriter problems;

  public GatewayProblemExceptionHandler(ProblemResponseWriter problems) {
    this.problems = problems;
  }

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable throwable) {
    if (exchange.getResponse().isCommitted()) {
      // Headers are already on the wire; re-writing would corrupt the response. Propagate so the
      // connection is closed rather than left half-written.
      return Mono.error(throwable);
    }

    ErrorCode errorCode = classify(throwable);

    // The full throwable goes to the log, correlated by id; only the code goes to the client.
    // Quoting the correlation id is how support turns a user's screenshot into this log line.
    log.error(
        "Gateway failure [{}] on {} {} -> {}",
        CorrelationIdWebFilter.correlationId(exchange),
        exchange.getRequest().getMethod(),
        exchange.getRequest().getPath().value(),
        errorCode.code(),
        throwable);

    return problems.write(exchange, errorCode, detailFor(errorCode), Map.of());
  }

  /**
   * Maps a throwable onto a platform error code.
   *
   * <p>Everything transport-shaped becomes 503 UPSTREAM_UNAVAILABLE rather than 500. The
   * distinction is operationally real: 503 says "the edge is fine, something behind it is not,
   * retry", and it is what a load balancer, a client retry policy and an on-call dashboard all key
   * off.
   */
  private static ErrorCode classify(Throwable throwable) {
    Throwable cause = throwable;
    while (cause != null) {
      if (cause instanceof ConnectException
          || cause instanceof TimeoutException
          || cause instanceof IOException) {
        return ErrorCode.UPSTREAM_UNAVAILABLE;
      }
      if (cause instanceof ResponseStatusException statusException) {
        // Covers the gateway's own NotFoundException (503, raised when an `lb://` service id has no
        // registered instance) as well as any deliberate 4xx a filter raised.
        HttpStatus status = HttpStatus.resolve(statusException.getStatusCode().value());
        if (status == HttpStatus.SERVICE_UNAVAILABLE || status == HttpStatus.GATEWAY_TIMEOUT) {
          return ErrorCode.UPSTREAM_UNAVAILABLE;
        }
        if (status == HttpStatus.NOT_FOUND) {
          return ErrorCode.RESOURCE_NOT_FOUND;
        }
        if (status == HttpStatus.UNAUTHORIZED) {
          return ErrorCode.UNAUTHENTICATED;
        }
        if (status == HttpStatus.FORBIDDEN) {
          return ErrorCode.FORBIDDEN;
        }
        if (status != null && status.is4xxClientError()) {
          return ErrorCode.VALIDATION_FAILED;
        }
        return ErrorCode.INTERNAL_ERROR;
      }
      cause = cause.getCause() == cause ? null : cause.getCause();
    }
    return ErrorCode.INTERNAL_ERROR;
  }

  private static String detailFor(ErrorCode errorCode) {
    return errorCode == ErrorCode.UPSTREAM_UNAVAILABLE
        ? "The upstream service is unavailable. Please retry shortly."
        : errorCode.title();
  }
}
