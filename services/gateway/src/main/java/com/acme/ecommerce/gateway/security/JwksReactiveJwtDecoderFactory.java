package com.acme.ecommerce.gateway.security;

import com.acme.ecommerce.common.security.AudienceValidator;
import com.acme.ecommerce.gateway.config.GatewayProperties;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Builds the gateway's {@link ReactiveJwtDecoder} with an EXPLICIT JWKS caching contract.
 *
 * <p>{@code NimbusReactiveJwtDecoder.withJwkSetUri(...)} would be one line, and its defaults are
 * wrong for an edge in three specific ways:
 *
 * <ol>
 *   <li>it has no refresh-ahead, so the request that finds the cache stale pays the fetch;
 *   <li>it has no forced-refresh rate limit, so the first burst after a key rotation turns into one
 *       JWKS fetch <em>per request</em> aimed at an auth-service that is already busy;
 *   <li>it has no outage tolerance, so auth-service being down for 30 seconds makes every token in
 *       the platform unverifiable - the gateway would convert a single-service outage into a total
 *       one.
 * </ol>
 *
 * <p>Nimbus' {@link JWKSourceBuilder} implements all three properly, so the stack below is
 * assembled by hand. Reading it outside-in: outage-tolerant wraps rate-limited wraps refresh-ahead
 * cache wraps retrying wraps {@link HttpJwkSetSource}.
 *
 * <p>NO EAGER FETCH AT STARTUP. The first token to arrive triggers the first fetch. That is what
 * lets the gateway boot before auth-service exists - which it must, because {@code depends_on:
 * auth-service} at the edge means auth-service's rollout is the platform's rollout.
 */
public final class JwksReactiveJwtDecoderFactory {

  private JwksReactiveJwtDecoderFactory() {
    throw new AssertionError("factory");
  }

  /**
   * Assembles the decoder.
   *
   * @param jwkSetSource the network-facing source; kept separate so tests can count fetches
   * @param properties issuer/audience contract and cache policy
   */
  public static ReactiveJwtDecoder create(
      HttpJwkSetSource<SecurityContext> jwkSetSource, GatewayProperties properties) {

    GatewayProperties.Jwks policy = properties.getJwks();

    JWKSource<SecurityContext> cachingSource =
        JWKSourceBuilder.<SecurityContext>create(jwkSetSource)
            // One retry on a transient failure, before any of the fallbacks below engage.
            .retrying(true)
            .cache(policy.getTtl().toMillis(), policy.getRefreshTimeout().toMillis())
            // `true` = refresh on a background thread. `false` would make the triggering request
            // wait for the refresh, which is exactly the latency spike refresh-ahead exists to
            // remove.
            .refreshAheadCache(policy.getRefreshAhead().toMillis(), true)
            // Collapses a burst of unknown-`kid` requests into one upstream fetch per interval.
            .rateLimited(policy.getRateLimitInterval().toMillis())
            // Keeps serving the last known key set while the source is unreachable.
            .outageTolerant(policy.getOutageTtl().toMillis())
            .build();

    NimbusReactiveJwtDecoder decoder =
        NimbusReactiveJwtDecoder.withJwkSource(keyLookup(cachingSource))
            // RS256 ONLY. Without an explicit allow-list the processor accepts whatever the token's
            // `alg` header asks for, which is the door the classic `alg` confusion attacks walk
            // through. auth-service signs RS256 and nothing else.
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();

    decoder.setJwtValidator(validator(properties.getJwt()));
    return decoder;
  }

  /**
   * Bridges Nimbus' synchronous key lookup onto the reactive stack.
   *
   * <p>The {@code boundedElastic} hop is not free and is not negotiable: a cache MISS performs a
   * blocking HTTP fetch, and doing that on a Netty event loop would stall every other request that
   * loop is multiplexing - one slow JWKS fetch becoming a gateway-wide latency event. Steady state
   * still makes ZERO network calls per request; what remains is a scheduler hand-off measured in
   * microseconds against a validation that is doing RSA verification anyway.
   */
  private static Function<SignedJWT, Flux<JWK>> keyLookup(JWKSource<SecurityContext> source) {
    return signedJwt -> {
      JWKSelector selector = new JWKSelector(JWKMatcher.forJWSHeader(signedJwt.getHeader()));
      return Mono.fromCallable(() -> source.get(selector, null))
          .subscribeOn(Schedulers.boundedElastic())
          .flatMapIterable(keys -> keys);
    };
  }

  /**
   * The claim checks. Signature validity alone means only "auth-service minted this at some point".
   *
   * <p>Issuer and audience are what make it mean "auth-service minted this, for us, now". Audience
   * in particular is not optional at an edge: without it, any token this issuer ever produced for
   * any relying party is accepted here and then propagated inwards as trusted identity headers.
   */
  private static OAuth2TokenValidator<Jwt> validator(GatewayProperties.Jwt jwt) {
    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
    // exp/nbf, with tolerance for NTP drift between auth-service and here.
    validators.add(new JwtTimestampValidator(GatewayProperties.Jwt.CLOCK_SKEW));
    validators.add(new JwtIssuerValidator(jwt.getIssuer()));
    validators.add(new AudienceValidator(jwt.getAudience()));
    return new DelegatingOAuth2TokenValidator<>(validators);
  }
}
