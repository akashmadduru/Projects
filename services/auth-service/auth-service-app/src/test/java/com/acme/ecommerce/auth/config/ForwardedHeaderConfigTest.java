package com.acme.ecommerce.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ecommerce.common.web.net.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

class ForwardedHeaderConfigTest {

  private static ForwardedHeaderFilter filter() {
    return new ForwardedHeaderConfig()
        .forwardedHeaderFilter(
            new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                .getBeanProvider(
                    org.springframework.boot.web.server.autoconfigure.servlet
                        .ForwardedHeaderFilterCustomizer.class))
        .getFilter();
  }

  private static HttpServletRequest seen(MockHttpServletRequest request) throws Exception {
    MockFilterChain chain = new MockFilterChain();
    filter().doFilter(request, new MockHttpServletResponse(), chain);
    return (HttpServletRequest) chain.getRequest();
  }

  private static MockHttpServletRequest base(String name, String value) {
    MockHttpServletRequest r = new MockHttpServletRequest("GET", "/x");
    r.setRemoteAddr("10.0.0.9");
    r.setRemoteHost("peer.local");
    r.setRemotePort(4321);
    r.addHeader("X-Forwarded-Host", "shop.example.com");
    r.addHeader("X-Forwarded-Proto", "https");
    if (name != null) {
      r.addHeader(name, value);
    }
    return r;
  }

  @Test
  @DisplayName("forwarded client headers never change the remote address; Host/Proto still apply")
  void peerWinsHostProtoKept() throws Exception {
    List<MockHttpServletRequest> requests =
        List.of(
            base("X-Forwarded-For", "9.9.9.9"),
            base("x-forwarded-for", "9.9.9.9"),
            base("Forwarded", "for=9.9.9.9"),
            base("FORWARDED", "For=\"[2001:db8::1]:4711\";proto=http"),
            withBoth());
    for (MockHttpServletRequest r : requests) {
      HttpServletRequest seen = seen(r);
      assertThat(seen.getRemoteAddr()).isEqualTo("10.0.0.9");
      assertThat(seen.getRemoteHost()).isEqualTo("peer.local");
      assertThat(seen.getRemotePort()).isEqualTo(4321);
      assertThat(ClientIp.resolveAsString(seen)).isEqualTo("10.0.0.9");
    }
    HttpServletRequest seen = seen(base("X-Forwarded-For", "9.9.9.9"));
    assertThat(seen.getServerName()).isEqualTo("shop.example.com");
    assertThat(seen.getScheme()).isEqualTo("https");
  }

  private static MockHttpServletRequest withBoth() {
    MockHttpServletRequest r = base("x-forwarded-for", "9.9.9.9");
    r.addHeader("Forwarded", "for=8.8.8.8");
    return r;
  }

  @Test
  @DisplayName("exactly one ForwardedHeaderFilter registration under the framework strategy")
  void oneRegistration() {
    new WebApplicationContextRunner()
        .withUserConfiguration(ForwardedHeaderConfig.class)
        .withPropertyValues("server.forward-headers-strategy=framework")
        .run(ctx -> assertThat(ctx.getBeansOfType(FilterRegistrationBean.class)).hasSize(1));
    new WebApplicationContextRunner()
        .withUserConfiguration(ForwardedHeaderConfig.class)
        .withPropertyValues("server.forward-headers-strategy=none")
        .run(ctx -> assertThat(ctx.getBeansOfType(FilterRegistrationBean.class)).isEmpty());
  }
}
