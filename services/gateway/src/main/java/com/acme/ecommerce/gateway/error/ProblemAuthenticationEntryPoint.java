package com.acme.ecommerce.gateway.error;

import com.acme.ecommerce.common.core.error.ErrorCode;
import java.util.Map;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Renders a 401 as problem+json instead of as an empty body.
 *
 * <p>Spring Security's default here is {@code BearerTokenServerAuthenticationEntryPoint}, which
 * sets {@code WWW-Authenticate} and a bodiless 401. An expired access token is the single most
 * common failure in a system with a 10-minute TTL, so "the most common error in the platform is the
 * one error the SPA cannot parse" is not a small papercut - it forces the client to special-case
 * one status code with no machine-readable reason.
 *
 * <p>The detail is deliberately uniform. "Signature invalid" versus "audience mismatch" versus
 * "expired" is an oracle for someone probing with a stolen or forged token; the real reason is in
 * the gateway log, correlated by id.
 */
@Component
public class ProblemAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

  private static final String DETAIL = "A valid Bearer access token is required for this endpoint.";

  private final ProblemResponseWriter problems;

  public ProblemAuthenticationEntryPoint(ProblemResponseWriter problems) {
    this.problems = problems;
  }

  @Override
  public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException exception) {
    // The challenge header is still correct and still useful to conformant clients; only the empty
    // body is being replaced.
    exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
    return problems.write(exchange, ErrorCode.UNAUTHENTICATED, DETAIL, Map.of());
  }
}
