package com.acme.ecommerce.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** No-JWT chain for /internal/** (X-Internal-Token; never routed by the gateway). */
@Configuration(proxyBeanMethods = false)
public class InternalSecurityConfig {

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public SecurityFilterChain internalFilterChain(
      HttpSecurity http, @Value("${notification.internal-token:}") String token) throws Exception {
    http.securityMatcher("/internal/**")
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
        .addFilterBefore(new TokenFilter(token), AuthorizationFilter.class);
    return http.build();
  }

  static class TokenFilter extends OncePerRequestFilter {
    private final byte[] expected;

    TokenFilter(String token) {
      this.expected = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String supplied = request.getHeader("X-Internal-Token");
      boolean ok =
          expected.length > 0
              && supplied != null
              && MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), expected);
      if (!ok) {
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Internal token required");
        return;
      }
      chain.doFilter(request, response);
    }
  }
}
