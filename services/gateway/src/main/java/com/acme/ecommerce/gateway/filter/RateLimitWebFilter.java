package com.acme.ecommerce.gateway.filter;

import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.gateway.error.ProblemResponseWriter;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Edge request rate limiting backed by Redis (fixed one-minute windows, shared across gateway
 * replicas). The key is the verified JWT subject when the request is authenticated, otherwise the
 * remote IP. Writes (POST/PUT/PATCH/DELETE) and reads have separate budgets. Auth endpoints keep
 * their own limiter in auth-service and are skipped here. FAIL-OPEN: a Redis outage never blocks
 * traffic.
 *
 * <p>Runs after Spring Security (order -100) so only a verified token can select the per-user key.
 */
@Component
public class RateLimitWebFilter implements WebFilter, Ordered {

  private static final Logger log = LoggerFactory.getLogger(RateLimitWebFilter.class);
  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final ReactiveStringRedisTemplate redis;
  private final ProblemResponseWriter problems;
  private final boolean enabled;
  private final long writesPerMinute;
  private final long readsPerMinute;
  private final int trustedProxyHops;

  private static final java.util.regex.Pattern IP_LITERAL =
      java.util.regex.Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

  public RateLimitWebFilter(
      ReactiveStringRedisTemplate redis,
      ProblemResponseWriter problems,
      @Value("${gateway.rate-limit.enabled:true}") boolean enabled,
      @Value("${gateway.rate-limit.writes-per-minute:30}") long writesPerMinute,
      @Value("${gateway.rate-limit.reads-per-minute:300}") long readsPerMinute,
      @Value("${gateway.rate-limit.trusted-proxy-hops:0}") int trustedProxyHops) {
    this.trustedProxyHops = trustedProxyHops;
    this.redis = redis;
    this.problems = problems;
    this.enabled = enabled;
    this.writesPerMinute = writesPerMinute;
    this.readsPerMinute = readsPerMinute;
  }

  @Override
  public int getOrder() {
    return -90;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    HttpMethod method = exchange.getRequest().getMethod();
    if (!enabled
        || HttpMethod.OPTIONS.equals(method)
        || path.startsWith("/api/auth/")
        || path.startsWith("/actuator")
        || path.startsWith("/__fallback")
        || path.startsWith("/.well-known")) {
      return chain.filter(exchange);
    }
    boolean write =
        HttpMethod.POST.equals(method)
            || HttpMethod.PUT.equals(method)
            || HttpMethod.PATCH.equals(method)
            || HttpMethod.DELETE.equals(method);
    long limit = write ? writesPerMinute : readsPerMinute;

    return subject(exchange)
        .flatMap(
            subject -> {
              long window = System.currentTimeMillis() / WINDOW.toMillis();
              String key = "rl:" + (write ? "w:" : "r:") + subject + ":" + window;
              return redis
                  .opsForValue()
                  .increment(key)
                  .flatMap(
                      count ->
                          (count == 1L ? redis.expire(key, WINDOW.plusSeconds(30)) : Mono.just(true))
                              .thenReturn(count))
                  .map(count -> count <= limit)
                  .onErrorResume(
                      e -> {
                        log.warn("Rate limiter unavailable, failing open: {}", e.toString());
                        return Mono.just(true);
                      });
            })
        .flatMap(
            allowed -> {
              if (allowed) {
                return chain.filter(exchange);
              }
              long retryAfter = WINDOW.toSeconds() - (System.currentTimeMillis() / 1000 % 60);
              exchange.getResponse().getHeaders().set("Retry-After", Long.toString(retryAfter));
              return problems.write(
                  exchange,
                  ErrorCode.RATE_LIMITED,
                  "Too many requests, retry in " + retryAfter + "s",
                  Map.of("retryAfterSeconds", retryAfter));
            });
  }

  private Mono<String> subject(ServerWebExchange exchange) {
    return ReactiveSecurityContextHolder.getContext()
        .map(ctx -> ctx.getAuthentication())
        .filter(a -> a != null && a.isAuthenticated() && a.getPrincipal() instanceof Jwt)
        .map(a -> "u:" + ((Jwt) a.getPrincipal()).getSubject())
        .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + remoteIp(exchange)));
  }

  /**
   * Client address used as the rate-limit key for anonymous callers.
   *
   * <p>Behind CloudFront and an ALB the socket peer is the load balancer, not the caller, so every
   * anonymous user would share one bucket. When {@code gateway.rate-limit.trusted-proxy-hops} is N
   * (> 0) the client is the N-th entry from the RIGHT of {@code X-Forwarded-For}: each trusted proxy
   * appends the address it received the request from, so only the entries on the left can be forged
   * by the caller and the entry N from the right cannot. Fewer than N entries, or anything that is
   * not an IP literal, falls back to the socket peer.
   */
  private String remoteIp(ServerWebExchange exchange) {
    if (trustedProxyHops > 0) {
      String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
      if (forwarded != null) {
        String[] parts = forwarded.split(",");
        if (parts.length >= trustedProxyHops) {
          String candidate = parts[parts.length - trustedProxyHops].trim();
          if (IP_LITERAL.matcher(candidate).matches()) {
            return candidate;
          }
        }
      }
    }
    InetSocketAddress addr = exchange.getRequest().getRemoteAddress();
    return addr == null || addr.getAddress() == null ? "unknown" : addr.getAddress().getHostAddress();
  }
}
