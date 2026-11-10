package com.acme.ecommerce.gateway.filter;

import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Establishes the correlation id for the request, before anything can fail.
 *
 * <p>Ordered {@value #ORDER} - after header sanitisation ({@code -300}) and BEFORE Spring Security
 * ({@code -100}). The security placement is the interesting one: a rejected request is precisely
 * the request someone will ask you about, and a 401 produced by an entry point that ran before this
 * filter would carry no id and be unfindable in the logs.
 *
 * <p>The id is published three ways because three different consumers need it:
 *
 * <ul>
 *   <li>an <b>exchange attribute</b>, for the gateway filters and the error handlers, which have
 *       the exchange but not necessarily a Reactor context;
 *   <li>the <b>Reactor context</b>, for anything reactive downstream of here;
 *   <li>a <b>response header</b>, so the caller can quote it in a support ticket.
 * </ul>
 *
 * <p>Deliberately NOT the SLF4J MDC / {@code CorrelationIdHolder} ThreadLocal that common-web uses:
 * on the reactive stack a request hops threads freely, and a ThreadLocal set here would be read by
 * an unrelated request later. That is worse than no correlation id, because it is a confidently
 * wrong one.
 */
@Component
public class CorrelationIdWebFilter implements WebFilter, Ordered {

  /** After sanitisation ({@code -300}), before Spring Security ({@code -100}). */
  public static final int ORDER = -250;

  /** Exchange attribute key holding the resolved id. */
  public static final String ATTRIBUTE = CorrelationIdWebFilter.class.getName() + ".CORRELATION_ID";

  /** Reactor context key holding the resolved id. */
  public static final String CONTEXT_KEY = "correlationId";

  private static final String HEADER = GatewayHeaders.CORRELATION_ID;

  /** Reads the id established for this exchange, generating one if the filter never ran. */
  public static String correlationId(ServerWebExchange exchange) {
    Object existing = exchange.getAttributes().get(ATTRIBUTE);
    return existing instanceof String value ? value : UUID.randomUUID().toString();
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String correlationId = resolve(exchange.getRequest().getHeaders().getFirst(HEADER));

    exchange.getAttributes().put(ATTRIBUTE, correlationId);

    // Registered as a beforeCommit callback rather than set outright. Callbacks run in REVERSE
    // registration order, and this filter registers first - so this one runs last and wins even if
    // a downstream service also returned an X-Correlation-Id that the proxy filter copied across.
    // A response carrying two different correlation ids is worse than useless.
    exchange
        .getResponse()
        .beforeCommit(
            () -> {
              exchange.getResponse().getHeaders().set(HEADER, correlationId);
              return Mono.empty();
            });

    return chain.filter(exchange).contextWrite(context -> context.put(CONTEXT_KEY, correlationId));
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  /**
   * Accepts an inbound id only if it is a well-formed UUID, and canonicalises it.
   *
   * <p>Not pedantry. This value is echoed into a response header and into every log line for the
   * request; accepting an arbitrary client string invites both header injection (CR/LF) and log
   * injection (a forged line that looks like a real one). Rejecting a malformed value and
   * generating a fresh one costs the client nothing but a trace it broke itself.
   */
  private static String resolve(String inbound) {
    if (inbound == null || inbound.isBlank()) {
      return UUID.randomUUID().toString();
    }
    try {
      // Canonicalised via toString() because UUID.fromString is lenient about field widths -
      // "1-1-1-1-1" parses - and only the canonical 36-character form should ever be emitted.
      return UUID.fromString(inbound.trim()).toString();
    } catch (IllegalArgumentException malformed) {
      return UUID.randomUUID().toString();
    }
  }
}
