package com.acme.ecommerce.gateway.web;

import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.gateway.error.ProblemResponseWriter;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Where a tripped circuit breaker (or a route-level timeout) lands.
 *
 * <p>Reached by an internal {@code forward:} from the {@code CircuitBreaker} route filter, never by
 * an external request: {@code ForwardRoutingFilter} re-dispatches the exchange through the WebFlux
 * handler, which is downstream of the security filter chain, so this path is not re-authorised. A
 * client that curls {@code /__fallback/auth} directly is subject to the normal {@code
 * anyExchange().authenticated()} rule and gets a 401 - which is the desired behaviour, and why the
 * path is not in the permit-all list.
 *
 * <p>The double-underscore prefix is deliberate: it cannot collide with a real product route, and
 * it is obviously internal in an access log.
 *
 * <p>Returns 503 with the platform's standard problem document. Not 500: nothing is wrong with the
 * gateway, and the distinction is what tells a client (and a retry policy) that trying again in a
 * moment is reasonable.
 */
@RestController
public class CircuitBreakerFallbackController {

  private static final String DETAIL_TEMPLATE =
      "The %s service is temporarily unavailable. Please retry shortly.";

  private final ProblemResponseWriter problems;

  public CircuitBreakerFallbackController(ProblemResponseWriter problems) {
    this.problems = problems;
  }

  /**
   * Renders the fallback.
   *
   * @param service logical name taken from the fallback URI. In normal operation it is a constant
   *     this codebase wrote into a route definition - but the path is also externally addressable,
   *     so it is treated as untrusted input and normalised before being echoed
   */
  @RequestMapping("/__fallback/{service}")
  public Mono<Void> fallback(@PathVariable String service, ServerWebExchange exchange) {
    String safe = normalise(service);
    return problems.write(
        exchange,
        ErrorCode.UPSTREAM_UNAVAILABLE,
        DETAIL_TEMPLATE.formatted(safe),
        Map.of("service", safe));
  }

  /**
   * Reduces the path segment to a short, boring identifier.
   *
   * <p>Jackson would escape anything dangerous on the way out, so this is not about JSON injection;
   * it is about not reflecting an arbitrary attacker-chosen string back to a browser in a document
   * some client will eventually render, and not letting a 2KB path segment into a log line.
   */
  private static String normalise(String service) {
    if (service == null || !service.matches("[A-Za-z0-9-]{1,32}")) {
      return "upstream";
    }
    return service;
  }
}
