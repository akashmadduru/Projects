package com.acme.ecommerce.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Keeps {@code server.forward-headers-strategy=framework} for {@code X-Forwarded-Host/Proto} (the
 * OIDC callback URL) but stops the framework filter from turning a client-supplied {@code
 * X-Forwarded-For} into {@code getRemoteAddr()}. Without this, the socket-peer fallback of {@code
 * ClientIp} (header absent) would be attacker-chosen. Replaces Boot's own registration, which backs
 * off when a {@code FilterRegistrationBean<ForwardedHeaderFilter>} exists.
 */
@Configuration(proxyBeanMethods = false)
public class ForwardedHeaderConfig {

  private static final String XFF = "X-Forwarded-For";

  @Bean
  FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter() {
    FilterRegistrationBean<ForwardedHeaderFilter> registration =
        new FilterRegistrationBean<>(new HostAndProtoOnlyForwardedHeaderFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  /** The framework filter, minus X-Forwarded-For. */
  static final class HostAndProtoOnlyForwardedHeaderFilter extends ForwardedHeaderFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      super.doFilterInternal(new WithoutXff(request), response, chain);
    }
  }

  private static final class WithoutXff extends HttpServletRequestWrapper {
    WithoutXff(HttpServletRequest request) {
      super(request);
    }

    @Override
    public String getHeader(String name) {
      return XFF.equalsIgnoreCase(name) ? null : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      return XFF.equalsIgnoreCase(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      List<String> names =
          Collections.list(super.getHeaderNames()).stream()
              .filter(n -> !XFF.equalsIgnoreCase(n))
              .toList();
      return Collections.enumeration(names);
    }
  }
}
