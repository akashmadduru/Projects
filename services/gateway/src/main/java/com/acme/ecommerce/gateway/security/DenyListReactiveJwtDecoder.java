package com.acme.ecommerce.gateway.security;

import com.acme.ecommerce.common.security.AccessTokenDenyList;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * Wraps the JWKS decoder with the access-token deny-list check (ADR 0005).
 *
 * <p>A revoked token fails with {@link BadJwtException}, the only decode failure Spring Security
 * maps to 401 ({@code InvalidBearerTokenException}); any other {@code JwtException} would become a
 * 5xx. FAIL OPEN: if Redis cannot be read the token passes, with a WARN and a counter, because
 * Redis is a soft dependency at the edge (the rate limiter does the same).
 */
public class DenyListReactiveJwtDecoder implements ReactiveJwtDecoder {

  private static final Logger log = LoggerFactory.getLogger(DenyListReactiveJwtDecoder.class);

  private final ReactiveJwtDecoder delegate;
  private final ReactiveStringRedisTemplate redis;
  private final MeterRegistry meters;

  public DenyListReactiveJwtDecoder(
      ReactiveJwtDecoder delegate, ReactiveStringRedisTemplate redis, MeterRegistry meters) {
    this.delegate = delegate;
    this.redis = redis;
    this.meters = meters;
  }

  @Override
  public Mono<Jwt> decode(String token) {
    return delegate.decode(token).flatMap(this::check);
  }

  private Mono<Jwt> check(Jwt jwt) {
    String sub = jwt.getSubject();
    if (sub == null || sub.isBlank()) {
      return Mono.error(new BadJwtException("The token has no subject"));
    }
    return Mono.defer(() -> redis.opsForValue().get(AccessTokenDenyList.key(sub)))
        .map(Long::parseLong)
        .map(cutoff -> AccessTokenDenyList.revoked(jwt.getIssuedAt(), cutoff))
        .defaultIfEmpty(false)
        .onErrorResume(
            e -> {
              meters.counter("gateway.access_deny.check.unavailable").increment();
              log.warn("Access-token deny-list unavailable, failing open: {}", e.toString());
              return Mono.just(false);
            })
        .flatMap(
            revoked -> {
              if (revoked) {
                meters.counter("gateway.access_deny.rejected").increment();
                return Mono.error(new BadJwtException("The token has been revoked"));
              }
              return Mono.just(jwt);
            });
  }
}
