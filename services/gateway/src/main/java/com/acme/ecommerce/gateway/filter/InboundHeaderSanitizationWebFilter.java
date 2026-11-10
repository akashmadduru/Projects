package com.acme.ecommerce.gateway.filter;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Destroys every client-supplied header in the platform's trusted namespace.
 *
 * <p>THE FIRST FILTER IN THE PROCESS (order {@value #ORDER}). That position is the whole point.
 * Downstream services authorise on {@code X-User-Id} because the gateway promises it can only come
 * from a validated token; the promise is only worth something if no component anywhere in this
 * process can observe the client's version of it. Stripping later - say, inside the identity filter
 * that overwrites them anyway - would leave a window in which access logs, metrics tags, tracing
 * baggage or any future filter could pick up an attacker-chosen value.
 *
 * <p>Runs BEFORE Spring Security ({@code -100}) for the same reason.
 *
 * <p>Note this is a plain {@link WebFilter}, not a gateway {@code GlobalFilter}: gateway filters
 * run inside {@code FilteringWebHandler}, which is already past Spring Security and past every
 * other WebFilter. There is no earlier hook than this one.
 */
@Component
public class InboundHeaderSanitizationWebFilter implements WebFilter, Ordered {

  /**
   * Before Spring Security's {@code WebFilterChainProxy} ({@code -100}) and before the correlation
   * filter ({@code -250}), so nothing at all sees the client's version of these headers.
   */
  public static final int ORDER = -300;

  private static final Logger log =
      LoggerFactory.getLogger(InboundHeaderSanitizationWebFilter.class);

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    ServerHttpRequest request = exchange.getRequest();

    List<String> spoofed =
        request.getHeaders().headerNames().stream()
            .filter(GatewayHeaders::isUntrustedInbound)
            .toList();

    if (spoofed.isEmpty()) {
      return chain.filter(exchange);
    }

    // WARN, not DEBUG: a client sending X-User-Id is either a broken integration or someone
    // probing for exactly this gap, and both are worth seeing without turning on debug logging.
    // The VALUES are never logged - echoing an attacker-chosen string into the log is its own
    // (log-injection) problem.
    log.warn(
        "Stripped {} untrusted inbound header(s) {} from {} {}",
        spoofed.size(),
        spoofed,
        request.getMethod(),
        request.getPath().value());

    ServerHttpRequest sanitized =
        request.mutate().headers(headers -> spoofed.forEach(headers::remove)).build();

    return chain.filter(exchange.mutate().request(sanitized).build());
  }

  @Override
  public int getOrder() {
    return ORDER;
  }
}
