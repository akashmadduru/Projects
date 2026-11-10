package com.acme.ecommerce.gateway.error;

import com.acme.ecommerce.common.core.error.ErrorCode;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Renders a 403 as problem+json.
 *
 * <p>403 and 401 mean genuinely different things to a client - "your token is fine but you may not
 * do this" versus "get a new token" - and an SPA that cannot tell them apart will helpfully log the
 * user out every time they click something they lack a role for. Distinct {@code code} members make
 * that branch trivial.
 */
@Component
public class ProblemAccessDeniedHandler implements ServerAccessDeniedHandler {

  private static final String DETAIL = "Your account does not have access to this resource.";

  private final ProblemResponseWriter problems;

  public ProblemAccessDeniedHandler(ProblemResponseWriter problems) {
    this.problems = problems;
  }

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException exception) {
    return problems.write(exchange, ErrorCode.FORBIDDEN, DETAIL, Map.of());
  }
}
