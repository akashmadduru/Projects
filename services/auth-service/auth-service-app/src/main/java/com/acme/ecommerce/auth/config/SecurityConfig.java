package com.acme.ecommerce.auth.config;

import com.acme.ecommerce.auth.api.AuthErrorCode;
import com.acme.ecommerce.auth.error.ProblemResponseWriter;
import com.acme.ecommerce.auth.oauth2.CookieOAuth2AuthorizationRequestRepository;
import com.acme.ecommerce.auth.oauth2.OidcLoginFailureHandler;
import com.acme.ecommerce.auth.oauth2.OidcLoginSuccessHandler;
import com.acme.ecommerce.auth.oauth2.PlatformOidcUserService;
import com.acme.ecommerce.auth.oauth2.RedirectTargetPolicy;
import com.acme.ecommerce.auth.ratelimit.RateLimitFilter;
import com.acme.ecommerce.auth.security.DatabaseUserDetailsService;
import com.acme.ecommerce.auth.token.JwtKeyProvider;
import com.acme.ecommerce.common.security.AudienceValidator;
import com.acme.ecommerce.common.security.PlatformJwtAuthenticationConverter;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The service's security wiring: what is public, how bearer tokens are validated, and how the
 * Google login leg is attached.
 *
 * <p>STATELESS. No {@code HttpSession} is created anywhere, including during the OAuth2 flow - see
 * {@link CookieOAuth2AuthorizationRequestRepository} for why that matters the moment there is a
 * second replica.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

  private static final String[] PUBLIC_ENDPOINTS = {
    "/api/auth/login",
    "/api/auth/register",
    "/api/auth/refresh",
    "/api/auth/logout",
    "/api/auth/password/forgot",
    "/api/auth/password/reset",
    "/api/auth/email/verify",
    "/api/auth/otp/request",
    "/api/auth/otp/verify",
    "/.well-known/jwks.json",
    "/oauth2/**",
    "/login/oauth2/**",
    "/actuator/health",
    "/actuator/health/**",
    "/actuator/info",
    "/actuator/prometheus"
  };

  /**
   * The filter chain.
   *
   * @param clientRegistrations present only when Google credentials are configured; the OAuth2 leg
   *     is installed conditionally so the service runs normally without them
   */
  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      JwtDecoder jwtDecoder,
      CorsConfigurationSource corsConfigurationSource,
      ProblemResponseWriter problems,
      ObjectProvider<ClientRegistrationRepository> clientRegistrations,
      ObjectProvider<PlatformOidcUserService> oidcUserService,
      ObjectProvider<OidcLoginSuccessHandler> successHandler,
      ObjectProvider<OidcLoginFailureHandler> failureHandler,
      ObjectProvider<CookieOAuth2AuthorizationRequestRepository> authorizationRequests)
      throws Exception {

    http
        // CSRF DISABLED, DELIBERATELY, WITH A REPLACEMENT CONTROL. This service
        // has no browser-rendered forms and no session; the only cookie-borne
        // credential is the refresh token, and it is SameSite=Lax + Path=/api/auth,
        // so a cross-site POST from an attacker's page does not carry it. CSRF
        // tokens would additionally require the SPA to fetch and echo a token on
        // an endpoint whose entire purpose is to work before the SPA has any
        // credentials. Residual risk is recorded in the service README.
        .csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        // No session, ever. An HttpSession here would be invisible state that
        // breaks the second a request lands on a different replica.
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(PUBLIC_ENDPOINTS)
                    .permitAll()
                    // Everything else - /me, /validate, any future admin endpoint -
                    // needs a valid bearer token. `anyRequest().authenticated()`
                    // rather than an allow-list of protected paths, so a new
                    // endpoint is secure by default and has to be opted OUT.
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    // THESE TWO MUST BE SET HERE, NOT ONLY ON exceptionHandling().
                    // The resource-server DSL installs its own
                    // BearerTokenAuthenticationEntryPoint, which wins over the
                    // http-level one for any request that carried a Bearer token.
                    // That entry point emits a WWW-Authenticate header and an EMPTY
                    // BODY - so without this, the single most common error in the
                    // system (an expired access token) comes back as a bodiless 401
                    // that the SPA cannot distinguish from any other failure, while
                    // every other error in the platform is problem+json.
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            problems.write(request, response, AuthErrorCode.AUTH_TOKEN_INVALID))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            problems.write(request, response, AuthErrorCode.AUTH_FORBIDDEN))
                    .jwt(
                        jwt ->
                            jwt.decoder(jwtDecoder)
                                // Shared converter: this service must interpret a
                                // token exactly as every other service does, or a
                                // role means one thing here and another downstream.
                                .jwtAuthenticationConverter(
                                    new PlatformJwtAuthenticationConverter())))
        .exceptionHandling(
            handling ->
                handling
                    // Rejections inside the filter chain never reach @RestControllerAdvice,
                    // so they are rendered as problem+json here instead of as Spring's
                    // default HTML page.
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            problems.write(request, response, AuthErrorCode.AUTH_TOKEN_INVALID))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            problems.write(request, response, AuthErrorCode.AUTH_FORBIDDEN)));

    ClientRegistrationRepository registrations = clientRegistrations.getIfAvailable();
    if (registrations != null) {
      http.oauth2Login(
          login ->
              login
                  .clientRegistrationRepository(registrations)
                  // THE critical override: cookie-based, not HttpSession-based.
                  .authorizationEndpoint(
                      endpoint ->
                          endpoint.authorizationRequestRepository(
                              authorizationRequests.getObject()))
                  .userInfoEndpoint(
                      endpoint -> endpoint.oidcUserService(oidcUserService.getObject()))
                  .successHandler(successHandler.getObject())
                  .failureHandler(failureHandler.getObject()));
    }

    return http.build();
  }

  /**
   * Password authentication.
   *
   * <p>{@code DaoAuthenticationProvider} is used rather than a hand-rolled comparison because it
   * already implements the two things a hand-rolled one always forgets: it hides {@code
   * UsernameNotFoundException} behind {@code BadCredentialsException}, and it runs a dummy hash
   * comparison when the user does not exist so that "no such account" and "wrong password" take the
   * same wall-clock time.
   */
  @Bean
  public AuthenticationManager authenticationManager(
      DatabaseUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder);
    // Explicit even though it is the default: turning it off is a one-line change
    // that silently reopens user enumeration AND disables the timing mitigation.
    provider.setHideUserNotFoundExceptions(true);
    return new ProviderManager(provider);
  }

  /**
   * Validates this service's OWN tokens for {@code /me} and {@code /validate}.
   *
   * <p>Verifies IN-PROCESS against the loaded keystore rather than over HTTP against its own JWKS
   * endpoint. Calling itself would make startup and every request depend on the service's own HTTP
   * listener being up, and would produce a genuinely delightful deadlock during a rolling restart.
   *
   * <p>Validation is otherwise identical to what every other resource server applies - signature,
   * timestamps, issuer AND audience. Audience in particular is not optional: without it, any token
   * this issuer ever minted for any service would be accepted here.
   */
  @Bean
  public JwtDecoder jwtDecoder(JwtKeyProvider keyProvider, AuthProperties properties) {
    JWKSource<SecurityContext> keySource = new ImmutableJWKSet<>(keyProvider.publicJwkSet());
    ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource));
    // Claim verification is Spring's job (below), not Nimbus's; leaving both
    // enabled means two different sets of rules, one of which nobody remembers.
    processor.setJWTClaimsSetVerifier(
        (claims, context) -> {
          /* delegated to Spring validators */
        });

    NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);

    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
    validators.add(JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()));
    properties.jwt().audiences().stream()
        .findFirst()
        .map(AudienceValidator::new)
        .ifPresent(validators::add);
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
    return decoder;
  }

  /**
   * CORS for the admin SPA.
   *
   * <p>Origins are DERIVED from {@code auth.oauth2.allowed-redirect-uris} rather than configured
   * separately: those are already the origins trusted to receive a login, and a second list would
   * inevitably drift out of step with the first.
   *
   * <p>{@code allowCredentials(true)} is required for the refresh cookie to be sent at all on a
   * cross-origin XHR, and it is precisely why the origin list must be explicit - the CORS spec
   * forbids combining it with {@code *}, and any implementation that reflects the request's Origin
   * instead is equivalent to having no policy.
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource(RedirectTargetPolicy redirectTargets) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(redirectTargets.allowedOrigins());
    configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id"));
    configuration.setAllowCredentials(true);
    configuration.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", configuration);
    return source;
  }

  /**
   * Registers the rate-limit filter ahead of the security chain.
   *
   * <p>Via a {@link FilterRegistrationBean} rather than as a {@code @Component}, so the order is
   * explicit. A bare filter bean is registered at {@code LOWEST_PRECEDENCE}, which would put the
   * throttle AFTER authentication - i.e. after the Argon2 verification it exists to prevent.
   */
  @Bean
  public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
      RateLimitFilter filter) {
    FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setOrder(RateLimitFilter.ORDER);
    registration.addUrlPatterns("/api/auth/*");
    registration.setName("rateLimitFilter");
    return registration;
  }

  /** The filter itself, not auto-registered (see {@link #rateLimitFilterRegistration}). */
  @Bean
  public RateLimitFilter rateLimitFilter(
      AuthProperties properties,
      com.acme.ecommerce.auth.web.RequestDeviceContext deviceContexts,
      ProblemResponseWriter problems,
      com.acme.ecommerce.auth.metrics.AuthMetrics metrics) {
    return new RateLimitFilter(properties, deviceContexts, problems, metrics);
  }
}
