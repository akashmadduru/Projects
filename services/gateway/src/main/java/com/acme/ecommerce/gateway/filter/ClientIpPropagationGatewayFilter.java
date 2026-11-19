package com.acme.ecommerce.gateway.filter;

import com.acme.ecommerce.common.core.net.ClientAddress;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Tells downstream services who the client is, via {@link GatewayHeaders#CLIENT_IP}.
 *
 * <p>A GlobalFilter (not a WebFilter) so it only runs for requests that matched a route and will be
 * proxied: 401/403/429 and unmatched paths never reach it. Order {@value #ORDER} is after identity
 * propagation (0) and before routing ({@code RouteToRequestUrlFilter} at 10000). The header is
 * {@code set} (replacing any value) when the address is known and removed otherwise, and {@link
 * InboundHeaderSanitizationWebFilter} has already stripped every client-sent {@code x-internal-*}.
 * Kept apart from {@link IdentityPropagationGatewayFilter}, whose only source is the token.
 */
@Component
public class ClientIpPropagationGatewayFilter implements GlobalFilter, Ordered {

  public static final int ORDER = 1;

  private final GatewayClientIp clientIp;

  public ClientIpPropagationGatewayFilter(GatewayClientIp clientIp) {
    this.clientIp = clientIp;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String ip = clientIp.resolve(exchange.getRequest()).map(ClientAddress::format).orElse(null);
    ServerHttpRequest request =
        exchange
            .getRequest()
            .mutate()
            .headers(
                headers -> {
                  if (ip == null) {
                    headers.remove(GatewayHeaders.CLIENT_IP);
                  } else {
                    headers.set(GatewayHeaders.CLIENT_IP, ip);
                  }
                })
            .build();
    return chain.filter(exchange.mutate().request(request).build());
  }

  @Override
  public int getOrder() {
    return ORDER;
  }
}
