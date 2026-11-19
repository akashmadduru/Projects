package com.acme.ecommerce.auth.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.autoconfigure.servlet.ForwardedHeaderFilterCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Keeps {@code server.forward-headers-strategy=framework} for {@code X-Forwarded-Host/Proto} (the
 * OIDC callback URL), but makes {@code getRemoteAddr()}, {@code getRemoteHost()} and {@code
 * getRemotePort()} always return the socket peer, so no forwarded header ({@code X-Forwarded-For}
 * or RFC 7239 {@code Forwarded: for=}) can choose the address {@code ClientIp} falls back to.
 * Registered like Boot's own filter (same condition, order and dispatcher types), which backs off
 * when a {@code FilterRegistrationBean<ForwardedHeaderFilter>} exists.
 */
@Configuration(proxyBeanMethods = false)
public class ForwardedHeaderConfig {

  @Bean
  @ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "framework")
  FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter(
      ObjectProvider<ForwardedHeaderFilterCustomizer> customizers) {
    ForwardedHeaderFilter filter = new PeerRemoteAddressForwardedHeaderFilter();
    customizers.orderedStream().forEach(customizer -> customizer.customize(filter));
    FilterRegistrationBean<ForwardedHeaderFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setDispatcherTypes(
        DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  /** The framework filter, with the remote address pinned to the original socket peer. */
  static final class PeerRemoteAddressForwardedHeaderFilter extends ForwardedHeaderFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String addr = request.getRemoteAddr();
      String host = request.getRemoteHost();
      int port = request.getRemotePort();
      super.doFilterInternal(
          request,
          response,
          (req, res) ->
              chain.doFilter(new PeerRemote((HttpServletRequest) req, addr, host, port), res));
    }
  }

  private static final class PeerRemote extends HttpServletRequestWrapper {
    private final String addr;
    private final String host;
    private final int port;

    PeerRemote(HttpServletRequest request, String addr, String host, int port) {
      super(request);
      this.addr = addr;
      this.host = host;
      this.port = port;
    }

    @Override
    public String getRemoteAddr() {
      return addr;
    }

    @Override
    public String getRemoteHost() {
      return host;
    }

    @Override
    public int getRemotePort() {
      return port;
    }
  }
}
