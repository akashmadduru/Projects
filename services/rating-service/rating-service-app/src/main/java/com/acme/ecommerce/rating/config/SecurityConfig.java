package com.acme.ecommerce.rating.config;

import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.security.AudienceValidator;
import com.acme.ecommerce.common.security.PlatformJwtAuthenticationConverter;
import com.acme.ecommerce.rating.error.ProblemResponseWriter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

/**
 * rating-service as a resource server in its own right.
 *
 * <p>THE GATEWAY IS A FILTER, NOT THE TRUST BOUNDARY. Every request that reaches {@code /api/**}
 * has already been authenticated at the edge, and this service authenticates it again anyway -
 * independently, from the bearer token, against the same JWKS. That is not redundancy for its own
 * sake: the gateway is not the only thing on the network that can reach this port. A misconfigured
 * route, a service mesh sidecar, a developer's port-forward or a future internal caller all bypass
 * it, and a service whose only protection is somebody else's filter is unprotected in all four
 * cases.
 *
 * <p>THE {@code X-User-*} HEADERS ARE NEVER CONSULTED FOR AN AUTHORIZATION DECISION. They exist for
 * logging and correlation. Trusting them would mean the security of this service reduces to "can
 * anything reach this port and set a header", which is exactly the property the paragraph above
 * refuses to accept.
 *
 * <p>STATELESS, NO CSRF. There is no session and no cookie-borne credential here - the only
 * credential is a bearer token, which a cross-site request cannot cause a browser to attach.
 *
 * <p>Review reads (the per-product list and summary) are public; everything else - including the
 * caller's own {@code /me} status and every write - requires a valid token. Ownership and the
 * purchase requirement are enforced in the service layer; the {@code ADMIN} role may moderate
 * (edit/delete) any review.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

  /**
   * Public review reads: the paged list and the summary (NOT {@code /me}). Mirrors the gateway's
   * public GET rule for the same two paths.
   */
  private static final String[] PUBLIC_READS = {
    "/api/ratings/products/*", "/api/ratings/products/*/summary"
  };

  /**
   * Actuator health/info/prometheus, which the orchestrator and the metrics scraper reach on the
   * internal network before any token exists.
   */
  private static final String[] PUBLIC_ENDPOINTS = {
    "/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus"
  };

  /** The filter chain. */
  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http, JwtDecoder jwtDecoder, ProblemResponseWriter problems) throws Exception {

    http.csrf(AbstractHttpConfigurer::disable)
        // No CORS configuration. This service is not reachable from a browser
        // except through the gateway, which owns the platform's single CORS
        // policy. A second policy here would emit a second
        // Access-Control-Allow-Origin header on every proxied response.
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(HttpMethod.GET, PUBLIC_READS)
                    .permitAll()
                    .requestMatchers(PUBLIC_ENDPOINTS)
                    .permitAll()
                    // `anyRequest().authenticated()` rather than an allow-list of
                    // protected paths, so a new endpoint is secure by default and
                    // has to be deliberately opted OUT. Role-scoped writes are
                    // layered on top via @PreAuthorize on the controllers.
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    // MUST BE SET HERE, not only on exceptionHandling(). The
                    // resource-server DSL installs its own
                    // BearerTokenAuthenticationEntryPoint, which wins for any
                    // request that carried a Bearer token and emits an EMPTY BODY.
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            problems.write(
                                request,
                                response,
                                ErrorCode.UNAUTHENTICATED,
                                "A valid bearer token is required"))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            problems.write(
                                request,
                                response,
                                ErrorCode.FORBIDDEN,
                                "You do not have permission to perform this action"))
                    .jwt(
                        jwt ->
                            jwt.decoder(jwtDecoder)
                                // Shared converter: a token must mean the same
                                // thing here as it does at the edge and in every
                                // other service, or a role means one thing in one
                                // process and another in the next.
                                .jwtAuthenticationConverter(
                                    new PlatformJwtAuthenticationConverter())))
        .exceptionHandling(
            handling ->
                handling
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            problems.write(
                                request,
                                response,
                                ErrorCode.UNAUTHENTICATED,
                                "Authentication is required"))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            problems.write(
                                request,
                                response,
                                ErrorCode.FORBIDDEN,
                                "You do not have permission to perform this action")));

    return http.build();
  }

  /**
   * The HTTP client the JWKS fetch goes through.
   *
   * <p>{@code @LoadBalanced}, so an {@code lb://AUTH-SERVICE/...} URL is resolved through Eureka ON
   * EVERY FETCH rather than captured once at startup. That late resolution is the whole point: the
   * auth-service address changes with every redeploy, and a URL frozen at rating-service boot would
   * be pinned to whichever instance happened to be registered at that moment - quite possibly none
   * at all, given compose bring-up order.
   *
   * <p>An absolute {@code rating.jwt.jwks-uri} bypasses this bean entirely (see {@link #jwtDecoder});
   * the load balancer would otherwise treat the URL's hostname as a service id.
   *
   * <p>Timeouts are mandatory, not defensive: {@code RestTemplate}'s defaults are INFINITE, so a
   * hung auth-service would park the fetching thread forever and, with Nimbus' cache expired, take
   * token validation down across the whole service.
   */
  @Bean
  @LoadBalanced
  public RestTemplate jwksRestTemplate(RatingProperties properties) {
    return new RestTemplate(requestFactory(properties));
  }

  /**
   * Validates the platform's access tokens.
   *
   * <p>LAZY BY CONSTRUCTION. Nimbus fetches the key set on the first token that arrives, not at
   * startup, so this service boots cleanly while auth-service is down or not yet registered - the
   * same property the gateway and product-service deliberately have. A resource server that cannot
   * start without its identity provider makes every auth-service rollout a fleet-wide outage.
   *
   * <p>Caching is Spring Security's default {@code JWKSourceBuilder} policy (cache + rate-limited
   * refresh), matching product-service's choice for the same reason: this service is on the
   * critical path of user calls only, not every request in the platform.
   */
  @Bean
  public JwtDecoder jwtDecoder(RatingProperties properties, RestTemplate jwksRestTemplate) {
    RatingProperties.Jwt jwt = properties.getJwt();

    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(jwt.effectiveJwksUri())
            // RS256 ONLY. Without an explicit allow-list the processor honours
            // whatever the token's `alg` header asks for, which is the door the
            // classic algorithm-confusion attacks walk through. auth-service
            // signs RS256 and nothing else.
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(
                jwt.hasStaticJwksUri()
                    ? new RestTemplate(requestFactory(properties))
                    : jwksRestTemplate)
            .build();

    decoder.setJwtValidator(validators(jwt));
    return decoder;
  }

  /**
   * The claim checks. A valid signature alone only means "auth-service minted this at some point".
   *
   * <p>Issuer and audience are what make it mean "auth-service minted this, for us, now". Audience
   * is not optional: without it, any token this issuer ever produced for any relying party is
   * accepted here.
   */
  private static OAuth2TokenValidator<Jwt> validators(RatingProperties.Jwt jwt) {
    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
    validators.add(new JwtTimestampValidator(RatingProperties.Jwt.CLOCK_SKEW));
    validators.add(new JwtIssuerValidator(jwt.getIssuer()));
    validators.add(new AudienceValidator(jwt.getAudience()));
    return new DelegatingOAuth2TokenValidator<>(validators);
  }

  private static SimpleClientHttpRequestFactory requestFactory(RatingProperties properties) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(properties.getJwt().getJwksConnectTimeout());
    factory.setReadTimeout(properties.getJwt().getJwksReadTimeout());
    return factory;
  }
}
