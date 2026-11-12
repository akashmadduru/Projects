package com.acme.ecommerce.notification.config;

import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.common.security.AudienceValidator;
import com.acme.ecommerce.common.security.PlatformJwtAuthenticationConverter;
import com.acme.ecommerce.notification.error.ProblemResponseWriter;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

/**
 * JWT resource server for the customer/admin APIs. /internal/** is guarded by the servlet-level
 * {@code InternalTokenFilter} and merely permitted here; the vapid key is public.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

  private static final String[] PUBLIC = {
    "/actuator/health",
    "/actuator/health/**",
    "/actuator/info",
    "/actuator/prometheus",
    "/internal/**",
    "/api/push/vapid-public-key"
  };

  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http, JwtDecoder jwtDecoder, ProblemResponseWriter problems) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            r ->
                r.requestMatchers(PUBLIC)
                    .permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            rs ->
                rs.authenticationEntryPoint(
                        (req, res, ex) ->
                            problems.write(
                                req, res, ErrorCode.UNAUTHENTICATED, "A valid bearer token is required"))
                    .accessDeniedHandler(
                        (req, res, ex) ->
                            problems.write(req, res, ErrorCode.FORBIDDEN, "Permission denied"))
                    .jwt(
                        jwt ->
                            jwt.decoder(jwtDecoder)
                                .jwtAuthenticationConverter(
                                    new PlatformJwtAuthenticationConverter())))
        .exceptionHandling(
            h ->
                h.authenticationEntryPoint(
                        (req, res, ex) ->
                            problems.write(
                                req, res, ErrorCode.UNAUTHENTICATED, "Authentication is required"))
                    .accessDeniedHandler(
                        (req, res, ex) ->
                            problems.write(req, res, ErrorCode.FORBIDDEN, "Permission denied")));
    return http.build();
  }

  @Bean
  @LoadBalanced
  public RestTemplate jwksRestTemplate() {
    return new RestTemplate(requestFactory());
  }

  @Bean
  public JwtDecoder jwtDecoder(
      RestTemplate jwksRestTemplate,
      @Value("${notification.jwt.issuer}") String issuer,
      @Value("${notification.jwt.audience}") String audience,
      @Value("${notification.jwt.jwks-service-id:AUTH-SERVICE}") String serviceId,
      @Value("${notification.jwt.jwks-uri:}") String staticUri) {
    boolean fixed = staticUri != null && !staticUri.isBlank();
    String uri = fixed ? staticUri : "lb://" + serviceId + "/.well-known/jwks.json";
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(uri)
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(fixed ? new RestTemplate(requestFactory()) : jwksRestTemplate)
            .build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            List.of(
                new JwtTimestampValidator(Duration.ofSeconds(30)),
                new JwtIssuerValidator(issuer),
                new AudienceValidator(audience))));
    return decoder;
  }

  private static SimpleClientHttpRequestFactory requestFactory() {
    SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
    f.setConnectTimeout(Duration.ofSeconds(2));
    f.setReadTimeout(Duration.ofSeconds(3));
    return f;
  }
}
