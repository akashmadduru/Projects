package com.acme.ecommerce.promotion.config;

import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.security.AudienceValidator;
import com.acme.ecommerce.common.security.PlatformJwtAuthenticationConverter;
import com.acme.ecommerce.promotion.error.ProblemResponseWriter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

/**
 * promotion-service as a resource server. {@code /internal/**} is guarded by {@link
 * InternalTokenFilter} (shared secret), the Stripe webhook by its signature, {@code
 * /api/payments/config} is public, everything else needs a valid bearer token.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

  private static final String[] PUBLIC_ENDPOINTS = {
    "/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
    "/api/promotions/offers",
    "/api/promotions/deals/active"
  };

  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      JwtDecoder jwtDecoder,
      ProblemResponseWriter problems,
      PromotionProperties properties)
      throws Exception {

    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(
            new InternalTokenFilter(properties.getInternalToken(), problems),
            BearerTokenAuthenticationFilter.class)
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(PUBLIC_ENDPOINTS)
                    .permitAll()
                    .requestMatchers("/internal/**")
                    .permitAll() // token checked by InternalTokenFilter
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
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

  /** JWKS fetch client, resolved through Eureka on every fetch. Timeouts are mandatory. */
  @Bean
  @LoadBalanced
  public RestTemplate jwksRestTemplate(PromotionProperties properties) {
    return new RestTemplate(requestFactory(properties));
  }

  /** Lazy JWKS-backed decoder (service boots while auth-service is down). */
  @Bean
  public JwtDecoder jwtDecoder(PromotionProperties properties, RestTemplate jwksRestTemplate) {
    PromotionProperties.Jwt jwt = properties.getJwt();

    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(jwt.effectiveJwksUri())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(
                jwt.hasStaticJwksUri()
                    ? new RestTemplate(requestFactory(properties))
                    : jwksRestTemplate)
            .build();

    decoder.setJwtValidator(validators(jwt));
    return decoder;
  }

  private static OAuth2TokenValidator<Jwt> validators(PromotionProperties.Jwt jwt) {
    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
    validators.add(new JwtTimestampValidator(PromotionProperties.Jwt.CLOCK_SKEW));
    validators.add(new JwtIssuerValidator(jwt.getIssuer()));
    validators.add(new AudienceValidator(jwt.getAudience()));
    return new DelegatingOAuth2TokenValidator<>(validators);
  }

  private static SimpleClientHttpRequestFactory requestFactory(PromotionProperties properties) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(properties.getJwt().getJwksConnectTimeout());
    factory.setReadTimeout(properties.getJwt().getJwksReadTimeout());
    return factory;
  }
}
