package com.acme.ecommerce.seller.config;

import com.acme.ecommerce.common.core.error.ErrorCode;
import com.acme.ecommerce.seller.error.ProblemResponseWriter;
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
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * No-JWT chain: /internal/** (X-Internal-Token; never routed by the gateway) and the PUBLIC
 * endpoints (seller profile, public feedback list, ad beacons) - a stale bearer token must not turn
 * a public read into a 401.
 */
@Configuration(proxyBeanMethods = false)
public class PublicSecurityConfig {

  private static boolean isPublic(HttpServletRequest r) {
    String uri = r.getRequestURI();
    String m = r.getMethod();
    if (uri.startsWith("/internal/") || uri.startsWith("/api/ads/")) {
      return true;
    }
    if ("GET".equals(m)) {
      return (uri.matches("/api/sellers/[^/]+") && !uri.equals("/api/sellers/me"))
          || uri.matches("/api/sellers/[^/]+/feedback");
    }
    return false;
  }

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public SecurityFilterChain publicFilterChain(
      HttpSecurity http,
      ProblemResponseWriter problems,
      @Value("${seller.internal-token:}") String token)
      throws Exception {
    RequestMatcher matcher = PublicSecurityConfig::isPublic;
    http.securityMatcher(matcher)
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
        .addFilterBefore(new TokenFilter(token, problems), AuthorizationFilter.class);
    return http.build();
  }

  static class TokenFilter extends OncePerRequestFilter {
    private final byte[] expected;
    private final ProblemResponseWriter problems;

    TokenFilter(String token, ProblemResponseWriter problems) {
      this.expected = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
      this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
      return !request.getRequestURI().startsWith("/internal/");
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
        problems.write(
            request, response, ErrorCode.INTERNAL_AUTH_FAILED, "Internal token required");
        return;
      }
      chain.doFilter(request, response);
    }
  }
}
