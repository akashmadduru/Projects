package com.acme.ecommerce.gateway.filter;

import com.acme.ecommerce.common.core.net.ClientAddress;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/**
 * The one place the gateway decides who the client is. Used by the rate limiter's anonymous key and
 * by {@link ClientIpPropagationGatewayFilter}, so the two can never disagree.
 *
 * <p>Behind CloudFront and an ALB the socket peer is the load balancer, not the caller. When {@code
 * gateway.rate-limit.trusted-proxy-hops} is N (> 0) the client is the N-th entry from the RIGHT of
 * {@code X-Forwarded-For}: each trusted proxy appends the address it received the request from, so
 * only the entries on the left can be forged by the caller. Fewer than N entries, or an entry that
 * is not an IP literal, falls back to the socket peer. An unresolvable peer yields empty.
 */
@Component
public class GatewayClientIp {

  private final int trustedProxyHops;

  public GatewayClientIp(
      @Value("${gateway.rate-limit.trusted-proxy-hops:0}") int trustedProxyHops) {
    this.trustedProxyHops = trustedProxyHops;
  }

  public Optional<InetAddress> resolve(ServerHttpRequest request) {
    if (trustedProxyHops > 0) {
      String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
      if (forwarded != null) {
        String[] parts = forwarded.split(",");
        if (parts.length >= trustedProxyHops) {
          Optional<InetAddress> client =
              ClientAddress.parse(parts[parts.length - trustedProxyHops]);
          if (client.isPresent()) {
            return client;
          }
        }
      }
    }
    InetSocketAddress peer = request.getRemoteAddress();
    return peer == null ? Optional.empty() : Optional.ofNullable(peer.getAddress());
  }
}
