package com.acme.ecommerce.gateway.filter;

import com.acme.ecommerce.common.security.Roles;
import java.util.stream.Collectors;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Injects the caller's identity into the proxied request, from validated token claims only.
 *
 * <p>A {@link GlobalFilter} at order {@value #ORDER}, NOT a {@code WebFilter}. The distinction is
 * load-bearing: gateway filters run inside {@code FilteringWebHandler}, which is where the request
 * that will actually be proxied gets assembled. A {@code WebFilter} runs before the gateway's
 * handler is even selected, and mutations it makes to the exchange are not what {@code
 * NettyRoutingFilter} ends up sending. Order 0 places this after authentication (Spring Security's
 * chain is a WebFilter at {@code -100}) and comfortably before routing ({@code
 * RouteToRequestUrlFilter} at {@code 10000}, {@code ReactiveLoadBalancerClientFilter} at {@code
 * 10150}).
 *
 * <p>THE ONLY SOURCE OF THESE HEADERS IS THE TOKEN. Nothing here reads a request header; {@link
 * InboundHeaderSanitizationWebFilter} has already removed the whole namespace anyway, so even a
 * future bug that tried to would find nothing. Belt and braces, on purpose - this is the control
 * that every downstream authorisation decision leans on.
 *
 * <p>On an anonymous route no identity headers are added at all - not blank ones. A downstream that
 * receives {@code X-User-Id: ""} has to decide what an empty user id means, and one of the possible
 * answers is "match everything".
 */
@Component
public class IdentityPropagationGatewayFilter implements GlobalFilter, Ordered {

  /** After Spring Security's chain (a WebFilter at {@code -100}), long before routing. */
  public static final int ORDER = 0;

  private static final String ROLE_SEPARATOR = ",";

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String correlationId = CorrelationIdWebFilter.correlationId(exchange);

    return ReactiveSecurityContextHolder.getContext()
        .map(SecurityContext::getAuthentication)
        .filter(Authentication::isAuthenticated)
        .filter(authentication -> authentication.getPrincipal() instanceof Jwt)
        .map(authentication -> withIdentity(exchange, authentication, correlationId))
        // Public route, or a route reached anonymously: correlation only. `defaultIfEmpty` covers
        // both the "no security context" and "context present but anonymous" cases.
        .defaultIfEmpty(withCorrelationOnly(exchange, correlationId))
        .flatMap(chain::filter);
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  private static ServerWebExchange withIdentity(
      ServerWebExchange exchange, Authentication authentication, String correlationId) {

    Jwt jwt = (Jwt) authentication.getPrincipal();

    ServerHttpRequest request =
        exchange
            .getRequest()
            .mutate()
            .headers(
                headers -> {
                  headers.set(GatewayHeaders.CORRELATION_ID, correlationId);
                  setIfPresent(headers, GatewayHeaders.USER_ID, jwt.getSubject());
                  setIfPresent(headers, GatewayHeaders.USER_EMAIL, jwt.getClaimAsString("email"));
                  setIfPresent(
                      headers, GatewayHeaders.AUTH_METHOD, jwt.getClaimAsString("auth_method"));
                  setIfPresent(headers, GatewayHeaders.USER_ROLES, roles(authentication));
                })
            .build();

    return exchange.mutate().request(request).build();
  }

  private static ServerWebExchange withCorrelationOnly(
      ServerWebExchange exchange, String correlationId) {
    ServerHttpRequest request =
        exchange
            .getRequest()
            .mutate()
            .headers(headers -> headers.set(GatewayHeaders.CORRELATION_ID, correlationId))
            .build();
    return exchange.mutate().request(request).build();
  }

  /**
   * Renders the caller's roles the way the {@code roles} CLAIM spells them - unprefixed.
   *
   * <p>The authorities held by the {@link Authentication} are {@code ROLE_}-prefixed, because
   * {@code RolesClaimAuthoritiesConverter} adds the prefix so {@code hasRole('ADMIN')} matches.
   * Propagating the prefixed form would mean a downstream service sees {@code ROLE_ADMIN} in the
   * header but {@code ADMIN} in the token it independently validates - two spellings of the same
   * fact, and eventually a comparison against the wrong one. Stripping it here keeps the header and
   * the claim literally identical.
   */
  private static String roles(Authentication authentication) {
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .filter(authority -> authority.startsWith(Roles.ROLE_PREFIX))
        .map(authority -> authority.substring(Roles.ROLE_PREFIX.length()))
        .collect(Collectors.joining(ROLE_SEPARATOR));
  }

  /**
   * Sets a header only if the claim actually carried a usable value.
   *
   * <p>{@link #sanitise(String)} is not decoration: {@code email} originates from a Google OIDC
   * profile and {@code roles} from the database, so neither is a compile-time constant. A value
   * containing CR/LF would be a response-splitting/header-injection primitive aimed at the
   * downstream service, which parses these headers with far less suspicion than it parses a body.
   */
  private static void setIfPresent(HttpHeaders headers, String name, String value) {
    String safe = sanitise(value);
    if (safe != null) {
      headers.set(name, safe);
    }
  }

  private static String sanitise(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      // Anything outside printable US-ASCII is rejected wholesale rather than escaped. These are
      // machine-readable identifiers, not display text; a value that needs escaping is a value
      // that should never have reached a header.
      if (c < 0x20 || c > 0x7E) {
        return null;
      }
    }
    return trimmed;
  }
}
