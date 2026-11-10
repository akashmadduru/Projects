package com.acme.ecommerce.gateway.config;

import com.acme.ecommerce.common.core.time.SystemTimeProvider;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.common.security.PlatformJwtAuthenticationConverter;
import com.acme.ecommerce.gateway.error.ProblemAccessDeniedHandler;
import com.acme.ecommerce.gateway.error.ProblemAuthenticationEntryPoint;
import com.acme.ecommerce.gateway.security.DiscoveryJwkSetUriResolver;
import com.acme.ecommerce.gateway.security.HttpJwkSetSource;
import com.acme.ecommerce.gateway.security.JwkSetUriResolver;
import com.acme.ecommerce.gateway.security.JwksReactiveJwtDecoderFactory;
import com.nimbusds.jose.proc.SecurityContext;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * The edge's authorization table and token-validation wiring.
 *
 * <p>THIS CLASS AND THE ROUTE TABLE IN {@code application.yml} ARE TWO HALVES OF ONE STATEMENT. A
 * route says where a request goes; this says who may send it. They are asserted against each other
 * by {@code RouteAuthorizationContractTest}, because the failure mode when they drift is silent: a
 * route keeps working, it just stops being protected.
 *
 * <p>The table is DENY-BY-DEFAULT. {@code anyExchange().authenticated()} at the bottom means a
 * route added tomorrow is closed until someone deliberately opens it - the opposite arrangement (an
 * explicit deny list) fails open on exactly the thing you forgot.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

  /** Reachable with no credentials at all. Each entry is a decision, not a convenience. */
  private static final String[] PUBLIC_PATHS = {
    // The login/refresh/logout endpoints: a client has no token yet, by definition.
    "/api/auth/login",
    "/api/auth/register",
    "/api/auth/refresh",
    "/api/auth/logout",
    // Public keys are public. Requiring a token here would be a circular dependency - you would
    // need a validated token to fetch the keys used to validate tokens.
    "/.well-known/jwks.json",
    // The Google leg. Not "unprotected": the OAuth2 `state` parameter and PKCE are the controls,
    // and they are enforced by auth-service, which owns the flow. The gateway cannot add anything
    // here except a way to break the redirect.
    "/oauth2/**",
    "/login/oauth2/**",
  };

  /**
   * The reactive security chain.
   *
   * <p>Stateless throughout: no session, no request cache, no security-context repository. Anything
   * that remembers a request between hops at the edge is invisible state that breaks the moment
   * there is a second gateway replica behind a load balancer.
   */
  @Bean
  public SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http,
      ReactiveJwtDecoder jwtDecoder,
      CorsConfigurationSource corsConfigurationSource,
      ProblemAuthenticationEntryPoint entryPoint,
      ProblemAccessDeniedHandler accessDeniedHandler) {

    return http
        // CSRF disabled, with a replacement control. The gateway authenticates from the
        // Authorization header only; it never makes an authorization decision based on a cookie, so
        // there is no ambient credential for a cross-site form post to ride on. The one cookie in
        // the system (the refresh token) is SameSite=Lax + Path=/api/auth and is consumed by
        // auth-service, which documents its own reasoning.
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        // THE ONLY CORS CONFIGURATION IN THE PROCESS. See GatewayProperties.Cors.
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .authorizeExchange(
            exchanges ->
                exchanges
                    // Liveness/readiness. Public here because dev runs without an internal
                    // interface to bind to; see the README for the production intent, which is to
                    // bind the management port to the internal network rather than to rely on this
                    // rule.
                    .pathMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    // EVERYTHING ELSE UNDER /actuator IS CLOSED AT THE EDGE. `denyAll`, not
                    // `authenticated`: there is no user role that should be able to read
                    // /actuator/env or trigger /actuator/shutdown from the public internet, so
                    // "authenticated" would be a promise that a future role change could quietly
                    // break. This must come after the health rule - first match wins.
                    .pathMatchers("/actuator/**")
                    .denyAll()
                    .pathMatchers(PUBLIC_PATHS)
                    .permitAll()
                    // GET /api/products/** is public (Phase 1F) - POST/PUT/DELETE remain protected.
                    // The gateway does not distinguish between GET methods when gating authorization,
                    // so only GET requests pass through. PUT and DELETE still hit authenticated()
                    // and are rejected by the gateway's default DENY-BY-DEFAULT posture.
                    .pathMatchers(HttpMethod.GET, "/api/products", "/api/products/**")
                    .permitAll()
                    // /api/auth/me, /api/auth/validate, /api/products POST/PUT/DELETE and anything added later.
                    .anyExchange()
                    .authenticated())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    // BOTH of these must be set on the resource-server spec, not only on
                    // exceptionHandling(). The resource-server DSL installs its own bearer-token
                    // entry point which wins for any request that carried an Authorization header -
                    // so setting them only below would leave the single most common error in the
                    // system (an expired token) returning a bodiless 401.
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
                    .jwt(
                        jwt ->
                            jwt.jwtDecoder(jwtDecoder)
                                // The SHARED converter. The gateway must read `roles` exactly the
                                // way every downstream service does, or a role means one thing at
                                // the edge and another behind it.
                                .jwtAuthenticationConverter(
                                    new ReactiveJwtAuthenticationConverterAdapter(
                                        new PlatformJwtAuthenticationConverter()))))
        .exceptionHandling(
            handling ->
                handling
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .build();
  }

  /**
   * Browser origin policy - configured HERE AND NOWHERE ELSE.
   *
   * <p>{@code spring.cloud.gateway.server.webflux.globalcors} is intentionally absent from {@code
   * application.yml}. With both configured, a preflight is answered twice and the response carries
   * two {@code Access-Control-Allow-Origin} headers; every browser treats that as a policy
   * violation and fails the request, with a console message that points at neither configuration.
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
    GatewayProperties.Cors cors = properties.cors();

    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(cors.allowedOrigins());
    configuration.setAllowedMethods(cors.allowedMethods());
    configuration.setAllowedHeaders(cors.allowedHeaders());
    configuration.setExposedHeaders(cors.exposedHeaders());
    // Required for the refresh cookie to travel on a cross-origin XHR at all - and the reason the
    // origin list above can never be `*`.
    configuration.setAllowCredentials(true);
    configuration.setMaxAge(cors.maxAge().toSeconds());

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  /**
   * The JWKS-backed decoder.
   *
   * <p>See {@link JwksReactiveJwtDecoderFactory} for why this is assembled by hand rather than with
   * {@code NimbusReactiveJwtDecoder.withJwkSetUri(...)}.
   */
  @Bean
  public ReactiveJwtDecoder reactiveJwtDecoder(
      GatewayProperties properties, HttpJwkSetSource<SecurityContext> jwkSetSource) {
    return JwksReactiveJwtDecoderFactory.create(jwkSetSource, properties);
  }

  /**
   * The network-facing JWKS source. A bean of its own so tests can assert the fetch count, which is
   * the only way to prove the rate-limited-refresh behaviour actually works.
   */
  @Bean
  public HttpJwkSetSource<SecurityContext> jwkSetSource(
      GatewayProperties properties, JwkSetUriResolver jwkSetUriResolver) {
    return new HttpJwkSetSource<>(
        jwkSetUriResolver, properties.jwks().connectTimeout(), properties.jwks().readTimeout());
  }

  /**
   * Where to find the JWKS document.
   *
   * <p>Discovery by default, so the keys are fetched from whichever auth-service instance is
   * currently registered rather than from a hostname frozen into configuration. An explicit {@code
   * gateway.jwt.jwks-uri} overrides it - used by tests, and by any deployment that has no registry.
   */
  @Bean
  public JwkSetUriResolver jwkSetUriResolver(
      GatewayProperties properties,
      ObjectProvider<ReactiveLoadBalancer.Factory<ServiceInstance>> loadBalancerFactory) {

    GatewayProperties.Jwt jwt = properties.jwt();
    if (jwt.hasStaticJwksUri()) {
      URI uri = URI.create(jwt.jwksUri());
      return () -> uri;
    }
    ReactiveLoadBalancer.Factory<ServiceInstance> factory = loadBalancerFactory.getObject();
    return new DiscoveryJwkSetUriResolver(factory, jwt.jwksServiceId(), jwt.jwksPath());
  }

  /**
   * Timestamps for problem documents.
   *
   * <p>Declared here rather than inherited from common-web's auto-configuration, because this
   * module deliberately does not depend on common-web (it is a servlet module).
   */
  @Bean
  public TimeProvider timeProvider() {
    return new SystemTimeProvider();
  }
}
